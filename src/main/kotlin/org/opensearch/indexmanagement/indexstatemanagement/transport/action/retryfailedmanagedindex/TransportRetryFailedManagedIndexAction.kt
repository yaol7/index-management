/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.indexstatemanagement.transport.action.retryfailedmanagedindex

import org.apache.logging.log4j.LogManager
import org.opensearch.ExceptionsHelper
import org.opensearch.OpenSearchSecurityException
import org.opensearch.OpenSearchStatusException
import org.opensearch.action.ActionListener
import org.opensearch.action.admin.cluster.state.ClusterStateRequest
import org.opensearch.action.admin.cluster.state.ClusterStateResponse
import org.opensearch.action.bulk.BulkRequest
import org.opensearch.action.bulk.BulkResponse
import org.opensearch.action.get.MultiGetRequest
import org.opensearch.action.get.MultiGetResponse
import org.opensearch.action.support.ActionFilters
import org.opensearch.action.support.HandledTransportAction
import org.opensearch.action.support.IndicesOptions
import org.opensearch.action.support.master.AcknowledgedResponse
import org.opensearch.action.update.UpdateRequest
import org.opensearch.client.node.NodeClient
import org.opensearch.cluster.ClusterState
import org.opensearch.cluster.block.ClusterBlockException
import org.opensearch.common.inject.Inject
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.XContentFactory
import org.opensearch.commons.ConfigConstants
import org.opensearch.commons.authuser.User
import org.opensearch.index.Index
import org.opensearch.index.IndexNotFoundException
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.INDEX_MANAGEMENT_INDEX
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.indexstatemanagement.model.managedindexmetadata.PolicyRetryInfoMetaData
import org.opensearch.indexmanagement.indexstatemanagement.opensearchapi.buildMgetMetadataRequest
import org.opensearch.indexmanagement.indexstatemanagement.opensearchapi.getManagedIndexMetadata
import org.opensearch.indexmanagement.indexstatemanagement.opensearchapi.mgetResponseToList
import org.opensearch.indexmanagement.indexstatemanagement.transport.action.ISMStatusResponse
import org.opensearch.indexmanagement.indexstatemanagement.transport.action.managedIndex.ManagedIndexAction
import org.opensearch.indexmanagement.indexstatemanagement.transport.action.managedIndex.ManagedIndexRequest
import org.opensearch.indexmanagement.indexstatemanagement.util.FailedIndex
import org.opensearch.indexmanagement.indexstatemanagement.util.isFailed
import org.opensearch.indexmanagement.indexstatemanagement.util.managedIndexMetadataID
import org.opensearch.indexmanagement.indexstatemanagement.util.updateEnableManagedIndexRequest
import org.opensearch.indexmanagement.util.IndexManagementException
import org.opensearch.indexmanagement.util.SecurityUtils.Companion.buildUser
import org.opensearch.rest.RestStatus
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportService

private val log = LogManager.getLogger(TransportRetryFailedManagedIndexAction::class.java)

@Suppress("SpreadOperator")
class TransportRetryFailedManagedIndexAction @Inject constructor(
    val client: NodeClient,
    transportService: TransportService,
    actionFilters: ActionFilters
) : HandledTransportAction<RetryFailedManagedIndexRequest, ISMStatusResponse>(
    RetryFailedManagedIndexAction.NAME, transportService, actionFilters, ::RetryFailedManagedIndexRequest
) {
    override fun doExecute(task: Task, request: RetryFailedManagedIndexRequest, listener: ActionListener<ISMStatusResponse>) {
        RetryFailedManagedIndexHandler(client, listener, request).start()
    }

    inner class RetryFailedManagedIndexHandler(
        private val client: NodeClient,
        private val actionListener: ActionListener<ISMStatusResponse>,
        private val request: RetryFailedManagedIndexRequest,
        private val user: User? = buildUser(client.threadPool().threadContext)
    ) {
        private val failedIndices: MutableList<FailedIndex> = mutableListOf()
        private val listOfMetadata: MutableList<ManagedIndexMetaData> = mutableListOf()
        private val listOfIndexToMetadata: MutableList<Pair<Index, ManagedIndexMetaData>> = mutableListOf()
        private val mapOfItemIdToIndex: MutableMap<Int, Index> = mutableMapOf()
        private lateinit var clusterState: ClusterState
        private val indicesManagedState: MutableMap<String, Boolean> = mutableMapOf()
        private var indicesToRetry = mutableMapOf<String, String>() // uuid: name

        @Suppress("SpreadOperator")
        fun start() {
            log.debug(
                "User and roles string from thread context: ${client.threadPool().threadContext.getTransient<String>(
                    ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT
                )}"
            )
            if (user == null) {
                // Security plugin is not enabled
                getClusterState()
            } else {
                validateAndGetClusterState()
            }
        }

        fun validateAndGetClusterState() {
            val request = ManagedIndexRequest().indices(*request.indices.toTypedArray())
            client.execute(
                ManagedIndexAction.INSTANCE,
                request,
                object : ActionListener<AcknowledgedResponse> {
                    override fun onResponse(response: AcknowledgedResponse) {
                        getClusterState()
                    }

                    override fun onFailure(e: java.lang.Exception) {
                        actionListener.onFailure(
                            IndexManagementException.wrap(
                                when (e is OpenSearchSecurityException) {
                                    true -> OpenSearchStatusException(
                                        "User doesn't have required index permissions on one or more requested indices: ${e.localizedMessage}",
                                        RestStatus.FORBIDDEN
                                    )
                                    false -> e
                                }
                            )
                        )
                    }
                }
            )
        }

        fun getClusterState() {
            val strictExpandIndicesOptions = IndicesOptions.strictExpand()

            val clusterStateRequest = ClusterStateRequest()
            clusterStateRequest.clear()
                .indices(*request.indices.toTypedArray())
                .metadata(true)
                .local(false)
                .masterNodeTimeout(request.masterTimeout)
                .indicesOptions(strictExpandIndicesOptions)

            client.threadPool().threadContext.stashContext().use {
                client.admin()
                    .cluster()
                    .state(
                        clusterStateRequest,
                        object : ActionListener<ClusterStateResponse> {
                            override fun onResponse(response: ClusterStateResponse) {
                                clusterState = response.state
                                val indexMetadatas = response.state.metadata.indices
                                indexMetadatas.forEach {
                                    indicesToRetry.putIfAbsent(it.value.indexUUID, it.key)
                                }
                                processResponse(response)
                            }

                            override fun onFailure(t: Exception) {
                                actionListener.onFailure(ExceptionsHelper.unwrapCause(t) as Exception)
                            }
                        }
                    )
            }
        }

        fun processResponse(clusterStateResponse: ClusterStateResponse) {
            val mReq = MultiGetRequest()
            clusterStateResponse.state.metadata.indices.map { it.value.indexUUID }
                .forEach { mReq.add(INDEX_MANAGEMENT_INDEX, it) }

            client.multiGet(
                mReq,
                object : ActionListener<MultiGetResponse> {
                    override fun onResponse(response: MultiGetResponse) {
                        // config index may not be initialized
                        val f = response.responses.first()
                        if (f.isFailed && f.failure.failure is IndexNotFoundException) {
                            indicesToRetry.forEach { (uuid, name) ->
                                failedIndices.add(FailedIndex(name, uuid, "This index is not being managed."))
                            }
                            actionListener.onResponse(ISMStatusResponse(0, failedIndices))
                            return
                        }

                        response.forEach {
                            indicesManagedState[it.id] = it.response.isExists
                        }

                        // get back metadata from config index
                        client.multiGet(buildMgetMetadataRequest(clusterState), ActionListener.wrap(::onMgetMetadataResponse, ::onFailure))
                    }

                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(ExceptionsHelper.unwrapCause(t) as Exception)
                    }
                }
            )
        }

        @Suppress("ComplexMethod")
        private fun onMgetMetadataResponse(mgetResponse: MultiGetResponse) {
            val metadataList = mgetResponseToList(mgetResponse)
            clusterState.metadata.indices.forEachIndexed { ind, it ->
                val indexMetaData = it.value
                val clusterStateMetadata = it.value.getManagedIndexMetadata()
                val mgetFailure = metadataList[ind]?.second
                val managedIndexMetadata: ManagedIndexMetaData? = metadataList[ind]?.first
                when {
                    indicesManagedState[indexMetaData.indexUUID] == false ->
                        failedIndices.add(FailedIndex(indexMetaData.index.name, indexMetaData.index.uuid, "This index is not being managed."))
                    mgetFailure != null ->
                        failedIndices.add(
                            FailedIndex(
                                indexMetaData.index.name, indexMetaData.index.uuid,
                                "Failed to get managed index metadata, $mgetFailure"
                            )
                        )
                    managedIndexMetadata == null -> {
                        if (clusterStateMetadata != null) {
                            failedIndices.add(
                                FailedIndex(
                                    indexMetaData.index.name, indexMetaData.index.uuid,
                                    "Cannot retry until metadata has finished migrating"
                                )
                            )
                        } else {
                            failedIndices.add(
                                FailedIndex(
                                    indexMetaData.index.name, indexMetaData.index.uuid,
                                    "This index has no metadata information"
                                )
                            )
                        }
                    }
                    !managedIndexMetadata.isFailed ->
                        failedIndices.add(
                            FailedIndex(
                                indexMetaData.index.name, indexMetaData.index.uuid,
                                "This index is not in failed state."
                            )
                        )
                    else ->
                        listOfMetadata.add(managedIndexMetadata)
                }
            }

            if (listOfMetadata.isNotEmpty()) {
                bulkEnableJob(listOfMetadata.map { it.indexUuid })
            } else {
                actionListener.onResponse(ISMStatusResponse(0, failedIndices))
            }
        }

        private fun bulkEnableJob(jobDocIds: List<String>) {
            val requestsToRetry = jobDocIds.map { updateEnableManagedIndexRequest(it) }
            val bulkRequest = BulkRequest().add(requestsToRetry)

            client.bulk(bulkRequest, ActionListener.wrap(::onEnableJobBulkResponse, ::onFailure))
        }

        private fun onEnableJobBulkResponse(bulkResponse: BulkResponse) {
            for (bulkItemResponse in bulkResponse) {
                val managedIndexMetaData = listOfMetadata.first { it.indexUuid == bulkItemResponse.id }
                if (bulkItemResponse.isFailed) {
                    failedIndices.add(FailedIndex(managedIndexMetaData.index, managedIndexMetaData.indexUuid, bulkItemResponse.failureMessage))
                } else {
                    listOfIndexToMetadata.add(
                        Pair(
                            Index(managedIndexMetaData.index, managedIndexMetaData.indexUuid),
                            managedIndexMetaData.copy(
                                stepMetaData = null,
                                policyRetryInfo = PolicyRetryInfoMetaData(false, 0),
                                actionMetaData = managedIndexMetaData.actionMetaData?.copy(
                                    failed = false,
                                    consumedRetries = 0,
                                    lastRetryTime = null,
                                    startTime = null
                                ),
                                transitionTo = request.startState,
                                info = mapOf("message" to "Pending retry of failed managed index")
                            )
                        )
                    )
                }
            }

            if (listOfIndexToMetadata.isNotEmpty()) {
                listOfIndexToMetadata.forEachIndexed { ind, (index, _) ->
                    mapOfItemIdToIndex[ind] = index
                }

                val updateMetadataRequests = listOfIndexToMetadata.map { (index, metadata) ->
                    val builder = metadata.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS, true)
                    UpdateRequest(INDEX_MANAGEMENT_INDEX, managedIndexMetadataID(index.uuid)).routing(index.uuid).doc(builder)
                }
                val bulkUpdateMetadataRequest = BulkRequest().add(updateMetadataRequests)

                client.bulk(bulkUpdateMetadataRequest, ActionListener.wrap(::onBulkUpdateMetadataResponse, ::onFailure))
            } else {
                actionListener.onResponse(ISMStatusResponse(0, failedIndices))
            }
        }

        private fun onBulkUpdateMetadataResponse(bulkResponse: BulkResponse) {
            val failedResponses = (bulkResponse.items ?: arrayOf()).filter { it.isFailed }
            failedResponses.forEach {
                val index = mapOfItemIdToIndex[it.itemId]
                if (index != null) {
                    failedIndices.add(FailedIndex(index.name, index.uuid, "Failed to update metadata for index ${index.name}"))
                }
            }

            val updated = (bulkResponse.items ?: arrayOf()).size - failedResponses.size
            actionListener.onResponse(ISMStatusResponse(updated, failedIndices))
        }

        fun onFailure(e: Exception) {
            try {
                if (e is ClusterBlockException) {
                    failedIndices.addAll(
                        listOfIndexToMetadata.map {
                            FailedIndex(it.first.name, it.first.uuid, "Failed to update due to ClusterBlockException. ${e.message}")
                        }
                    )
                }

                actionListener.onResponse(ISMStatusResponse(0, failedIndices))
            } catch (inner: Exception) {
                inner.addSuppressed(e)
                log.error("Failed to send failure response", inner)
            }
        }
    }
}
