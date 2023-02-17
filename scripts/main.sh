#!/usr/bin/env bash

MAIN_SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
source ${MAIN_SCRIPT_DIR}/init.sh

if [[ -z ${COMMON_LOG_DIR} ]];then
    COMMON_LOG_DIR=${WORKING_DIR}/$(date +"%d-%m-%Y_%T.%N")
    declare -r COMMON_LOG_DIR=${COMMON_LOG_DIR}
fi
mkdir -p ${COMMON_LOG_DIR}

trap trapHandler INT QUIT TERM
trapHandler() {
    log "In trap..."
    stopClient
    stopCluster
    postFinishActions
    exit 1
}

# Start solr servers
startServers() {

    dropCacheOnSolrNodes

    local ZOOKEEPER_ENSEMBLE_NODES_PORTS=""
    local SEP=""
    for (( i=0; i<${#ZOOKEEPER_AWS_NODES[@]}; i++ ));do
        [[ $i -eq 0 ]] || SEP=","
        ZOOKEEPER_ENSEMBLE_NODES_PORTS="${ZOOKEEPER_ENSEMBLE_NODES_PORTS}${SEP}${ZOOKEEPER_AWS_NODE_NAMES[i]}:2181"
    done

    for (( i=0; i<${#SOLR_AWS_SERVER[@]}; i++ ));do
        log_wrap "Starting Solr node : ${SOLR_AWS_SERVER[i]} (${SOLR_AWS_SERVER_NAMES[i]})"

         COMMAND="JAVA_HOME=${JAVA_HOME} \
            SOLR_JAVA_MEM='${SOLR_JAVA_MEM:-"-Xms60g -Xmx60g"}' \
            GC_TUNE='${GC_TUNE}' \
            /opt/solr/bin/solr restart -h ${SOLR_AWS_SERVER_NAMES[i]} -p 8983 -z ${ZOOKEEPER_ENSEMBLE_NODES_PORTS}"

        log_wrap1 "-" "${COMMAND}"
        ssh -i ${AWS_PRIVATE_KEY} -o StrictHostKeyChecking=no \
            ${AWS_USER}@${SOLR_AWS_SERVER[i]} \
            "${COMMAND}"
        [[ $? -ne 0 ]] && fail "Solr node start failed"
    done
}

stopServers() {
    local COMMAND
    COMMAND="JAVA_HOME=${JAVA_HOME} /opt/solr/bin/solr stop -p 8983"

    log_wrap "Stopping all Solr nodes"
    runCommandOnSolrNodes "${COMMAND}"
}

startClient() {
    local COMMAND

    local CACHE_SETTINGS="query.queryResultCache.size=10000,query.queryResultCache.autowarmCount=10000,updateHandler.autoCommit.maxTime=60000"
    [[ -z ${CACHE_SETTINGS} ]] || (
        updateCacheSettings "${CACHE_SETTINGS}"
    )

    log_wrap "Starting client/loadgenerator on node : ${SOLR_AWS_CLIENT_NAMES[0]} (${SOLR_CLIENTS[0]})"

    scp -i ${AWS_PRIVATE_KEY} -o StrictHostKeyChecking=no \
        ${WORKING_DIR}/${SOLR_BENCHMARK_JAR} \
        ${MAIN_SCRIPT_DIR}/../bench-config.yaml \
        ${MAIN_SCRIPT_DIR}/../test-config.yaml \
        ${MAIN_SCRIPT_DIR}/../logging.properties \
        ${AWS_USER}@${SOLR_CLIENTS[0]}:/home/${AWS_USER}/

    COMMAND="${JAVA_HOME}/bin/java \
        -Xmx10g \
        -DqueryType=${QUERY_TYPE} \
        -Xloggc:/home/${AWS_USER}/gc_client.log \
        -Djava.util.logging.config.file=/home/${AWS_USER}/logging.properties \
        -cp /home/${AWS_USER}/${SOLR_BENCHMARK_JAR} \
        org.bench.solr.SolrBenchmark \
        /home/${AWS_USER}/bench-config.yaml"

    runCommandOnClientNode "${COMMAND}"
    stopClient
}

stopClient() {
    log_wrap "Stopping client on: ${SOLR_AWS_CLIENT_NAMES[0]} (${SOLR_CLIENTS[0]})"
    runCommandOnClientNode "kill -9 \`ps -ef | grep java | grep -v grep | awk '{print \$2}'\`"
}

preStartActions() {
    checkAndInstallJDKBundles
    killJavaProcs
    cleanLogsOnAWS
}

postFinishActions() {
    copyLogsFromAWS
    cleanLogsOnAWS
    killJavaProcs
}

startBenchmark() {
    preStartActions

    startCluster

    startClient

    stopCluster

    postFinishActions
}

${1}