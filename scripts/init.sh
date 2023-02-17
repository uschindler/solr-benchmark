#!/usr/bin/env bash

INIT_SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
source ${INIT_SCRIPT_DIR}/utils.sh

if [[ -z "${INIT_ALREADY_SOURCED}" ]];then # Don't source if already sourced
    declare -r WORKING_DIR=${INIT_SCRIPT_DIR}/../
    cd ${WORKING_DIR}

    declare -r COLLECTION_NAME="test"

    declare -r SOLR_DIST_URL=https://archive.apache.org/dist/solr/solr/9.0.0/solr-9.0.0.tgz

    declare -r SOLR_DIST=`echo ${SOLR_DIST_URL##*/}`

    declare -r SOLR=`echo ${SOLR_DIST/.tgz/}`

    declare -r WIKI_DUMP_URL=https://cdn.azul.com/blogs/datasets/solr/wiki.json.gz
    declare -r QUERY_FILES_URL=https://cdn.azul.com/blogs/datasets/solr/queryFiles.tar


    declare -r SOLR_INSTALL_COMMAND=$(cat <<- END
ls /opt/solr/bin/solr 1>/dev/null 2>&1 || (
    echo "Solr may not have been installed. Downloading and installing it ...";
    wget -q ${SOLR_DIST_URL} -O ${SOLR_DIST};
    tar -xf ${SOLR_DIST};
    sudo bash \${HOME}/${SOLR}/bin/install_solr_service.sh \${HOME}/${SOLR}.tgz -i /opt -d /var/solr -u \${USER} -s solr -p 8983 -n;
)
END
)
    declare -r INIT_ALREADY_SOURCED=true
fi
