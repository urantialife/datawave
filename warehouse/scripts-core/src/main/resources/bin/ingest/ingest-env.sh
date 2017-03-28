#!/bin/bash

#Directory in which to place the lock files
export LOCK_FILE_DIR=${lock.file.dir}

if [[ ! -d ${LOCK_FILE_DIR} || ! -w ${LOCK_FILE_DIR} ]]; then
  "Lock file directory ${LOCK_FILE_DIR} does not exist or is not writable. Exiting..."
  exit -1
fi

#echo "Using ${CONFIGURATION} configuration"

. ../system/header.sh

# regex matching changed since bash 3.1....ensure we are forward compatible
shopt -s compat31 > /dev/null 2>&1

# load the external password specifications.  The following needs to be defined in this script: PASSWORD, TRUSTSTORE_PASSWORD, KEYSTORE_PASSWORD
# Optionally these additional passwords need to be defined: CACHE_PWORD, AGEOFF_SERVER_CERT_PASS
function checkForVar (){
   found=`cat $1 | egrep " $2 *="`
   if [[ "$found" == "" ]]; then
      echo "$2,"
   fi
}

PASSWORD_INGEST_ENV="${PASSWORD_INGEST_ENV}"
if [[ "$PASSWORD_INGEST_ENV" != "" ]]; then
   if [[ -e ${PASSWORD_INGEST_ENV} ]]; then
      missing=\
"$(checkForVar $PASSWORD_INGEST_ENV "PASSWORD")\
$(checkForVar $PASSWORD_INGEST_ENV "CACHE_PWORD")\
$(checkForVar $PASSWORD_INGEST_ENV "TRUSTSTORE_PASSWORD")\
$(checkForVar $PASSWORD_INGEST_ENV "KEYSTORE_PASSWORD")\
$(checkForVar $PASSWORD_INGEST_ENV "AGEOFF_SERVER_CERT_PASS")"
      if [[ "$missing" != "" ]]; then
         echo "FATAL: ${PASSWORD_INGEST_ENV} is missing the following definitions: $missing"
         exit 10
      fi
      . "$PASSWORD_INGEST_ENV"
   else
      echo "FATAL: ${PASSWORD_INGEST_ENV} was not found  Please create that script on this system."
      exit 10
   fi
else
   echo "FATAL: PASSWORD_INGEST_ENV was not defined.  Please define this in your deployment properties and create that script on this system."
   echo "   e.g. /opt/datawave-ingest/ingest-passwd.sh:"
   echo "        export PASSWORD=\"accumulo_passwd\""
   echo "        export CACHE_PWORD=\"accumulo_passwd\""
   echo "        export TRUSTSTORE_PASSWORD=\"trust_passwd\""
   echo "        export KEYSTORE_PASSWORD=\"cert_passwd\""
   echo  "        export AGEOFF_SERVER_CERT_PASS=\"cert_passwd\""
   exit 10
fi

# if a deployment specific environment has been specified, then load it
ADDITIONAL_INGEST_ENV="${ADDITIONAL_INGEST_ENV}"
if [[ "$ADDITIONAL_INGEST_ENV" != "" ]]; then
   . "$ADDITIONAL_INGEST_ENV"
fi

ADDITIONAL_INGEST_LIBS="${ADDITIONAL_INGEST_LIBS}"

# Provides a method to run map-file-bulk-loader as a different user
MAP_FILE_LOADER_COMMAND_PREFIX="${MAP_FILE_LOADER_COMMAND_PREFIX}"
MAP_FILE_LOADER_EXTRA_ARGS="${MAP_FILE_LOADER_EXTRA_ARGS}"
MAP_FILE_LOADER_SEPARATE_START="${MAP_FILE_LOADER_SEPARATE_START}"
MAP_FILE_LOADER_SEPARATE_START="${MAP_FILE_LOADER_SEPARATE_START:-false}"
RCPT_TO="${RCPT_TO}"
SEND_JOB_EMAIL_DISABLED="${SEND_JOB_EMAIL_DISABLED}"

HADOOP_HOME="${HADOOP_HOME}"
MAPRED_HOME="${MAPRED_HOME}"
MAPRED_HOME="${MAPRED_HOME:-$HADOOP_HOME}"

USERNAME="${USERNAME}"

WAREHOUSE_ACCUMULO_HOME="${WAREHOUSE_ACCUMULO_HOME}"
WAREHOUSE_ACCUMULO_LIB="${WAREHOUSE_ACCUMULO_LIB}"
WAREHOUSE_ACCUMULO_BIN="${WAREHOUSE_ACCUMULO_BIN}"
WAREHOUSE_ACCUMULO_LIB="${WAREHOUSE_ACCUMULO_LIB:-$WAREHOUSE_ACCUMULO_HOME/lib}"
WAREHOUSE_ACCUMULO_BIN="${WAREHOUSE_ACCUMULO_BIN:-$WAREHOUSE_ACCUMULO_HOME/bin}"
WAREHOUSE_HDFS_NAME_NODE="${WAREHOUSE_HDFS_NAME_NODE}"
WAREHOUSE_NAME_BASE_DIR="${WAREHOUSE_NAME_BASE_DIR}"
WAREHOUSE_JOBTRACKER_NODE="${WAREHOUSE_JOBTRACKER_NODE}"
WAREHOUSE_ZOOKEEPERS="${WAREHOUSE_ZOOKEEPERS}"
WAREHOUSE_INSTANCE_NAME="${WAREHOUSE_INSTANCE_NAME}"
# setting these two times may seem unnecessary, but the first one is required if
# the property is set in the assembly properties (see datawave_deploy).  The second
# one is needed if it is not set explicitly but HADOOP_HOME is.
WAREHOUSE_HADOOP_HOME="${WAREHOUSE_HADOOP_HOME}"
WAREHOUSE_HADOOP_HOME="${WAREHOUSE_HADOOP_HOME:-$HADOOP_HOME}"
WAREHOUSE_MAPRED_HOME="${WAREHOUSE_MAPRED_HOME}"
WAREHOUSE_MAPRED_HOME="${WAREHOUSE_MAPRED_HOME:-$MAPRED_HOME}"
WAREHOUSE_HADOOP_CONF="${WAREHOUSE_HADOOP_CONF}"
WAREHOUSE_HADOOP_CONF="${WAREHOUSE_HADOOP_CONF:-$WAREHOUSE_HADOOP_HOME/conf}"

INGEST_ACCUMULO_HOME="${INGEST_ACCUMULO_HOME}"
INGEST_HDFS_NAME_NODE="${INGEST_HDFS_NAME_NODE}"
INGEST_JOBTRACKER_NODE="${INGEST_JOBTRACKER_NODE}"
INGEST_ZOOKEEPERS="${INGEST_ZOOKEEPERS}"
INGEST_INSTANCE_NAME="${INGEST_INSTANCE_NAME}"
# setting these two times may seem unnecessary, but the first one is required if
# the property is set in the assembly properties (see datawave_deploy).  The second
# one is needed if it is not set explicitly but HADOOP_HOME is.
INGEST_HADOOP_HOME="${INGEST_HADOOP_HOME}"
INGEST_HADOOP_HOME="${INGEST_HADOOP_HOME:-$HADOOP_HOME}"
INGEST_MAPRED_HOME="${INGEST_MAPRED_HOME}"
INGEST_MAPRED_HOME="${INGEST_MAPRED_HOME:-$MAPRED_HOME}"
INGEST_HADOOP_CONF="${INGEST_HADOOP_CONF}"
INGEST_HADOOP_CONF="${INGEST_HADOOP_CONF:-$INGEST_HADOOP_HOME/conf}"

# STAGING_HOSTS is a comma delimited list of hosts
STAGING_HOSTS="${STAGING_HOSTS}"
INGEST_HOST="${INGEST_HOST}"
ROLLUP_HOST="${ROLLUP_HOST}"

# hadoop and child opts for ingest
MAPRED_INGEST_OPTS="${MAPRED_INGEST_OPTS}"
HADOOP_INGEST_OPTS="${HADOOP_INGEST_OPTS}"
CHILD_INGEST_OPTS="${CHILD_INGEST_OPTS}"

LIVE_CHILD_MAX_MEMORY_MB="${LIVE_CHILD_MAX_MEMORY_MB}"
BULK_CHILD_MAX_MEMORY_MB="${BULK_CHILD_MAX_MEMORY_MB}"
MISSION_MGMT_CHILD_MAP_MAX_MEMORY_MB="${MISSION_MGMT_CHILD_MAP_MAX_MEMORY_MB}"

# the next four comma delimited lists work in concert with each other and must align
# (i.e. the first of the POLLER_DATA_TYPES pulls from the first of the POLLER_INPUT_DIRECTORIES and
#  outputs into the first of the POLLER_OUTPUT_DIRECTORIES using the first of the POLLER_CLIENT_OPTS)
# the POLLER_CLIENT_EXTRA_OPTS are applied to every poller
${EXTRA_POLLER_VARIABLES}
POLLER_DATA_TYPES="${POLLER_DATA_TYPES}"
POLLER_INPUT_DIRECTORIES="${POLLER_INPUT_DIRECTORIES}"
POLLER_OUTPUT_DIRECTORIES="${POLLER_OUTPUT_DIRECTORIES}"
POLLER_CLIENT_OPTS="${POLLER_CLIENT_OPTS}"
POLLER_CLIENT_EXTRA_OPTS="${POLLER_CLIENT_EXTRA_OPTS}"
POLLER_JMX_PORT_START="${POLLER_JMX_PORT_START}"

POLLER_FILE_BLOCK_SIZE_MB="${POLLER_FILE_BLOCK_SIZE_MB}"
POLLER_BULK_FILE_BLOCK_SIZE_MB="${POLLER_BULK_FILE_BLOCK_SIZE_MB}"
POLLER_LIVE_FILE_BLOCK_SIZE_MB="${POLLER_LIVE_FILE_BLOCK_SIZE_MB}"

POLLER_MAX_OUTPUT_RECORDS="${POLLER_MAX_OUTPUT_RECORDS}"
POLLER_BULK_MAX_OUTPUT_RECORDS="${POLLER_BULK_MAX_OUTPUT_RECORDS}"
POLLER_LIVE_MAX_OUTPUT_RECORDS="${POLLER_LIVE_MAX_OUTPUT_RECORDS}"

POLLER_MAX_TOTAL_EVENT_OUTPUT_MB="${POLLER_MAX_TOTAL_EVENT_OUTPUT_MB}"
POLLER_BULK_MAX_TOTAL_EVENT_OUTPUT_MB="${POLLER_BULK_MAX_TOTAL_EVENT_OUTPUT_MB}"
POLLER_LIVE_MAX_TOTAL_EVENT_OUTPUT_MB="${POLLER_LIVE_MAX_TOTAL_EVENT_OUTPUT_MB}"

ERROR_POLLER_DATA_TYPE="${ERROR_POLLER_DATA_TYPE}"
ERROR_POLLER_TABLE_NAME="${ERROR_POLLER_TABLE_NAME}"
ERROR_POLLER_INDEXTABLE_NAME="${ERROR_POLLER_INDEXTABLE_NAME}"
ERROR_POLLER_INPUT_DIRECTORIES="${ERROR_POLLER_INPUT_DIRECTORIES}"
ERROR_POLLER_OUTPUT_DIRECTORIES="${ERROR_POLLER_OUTPUT_DIRECTORIES}"
ERROR_POLLER_CLIENT_OPTS="${ERROR_POLLER_CLIENT_OPTS}"

POLLER_METRICS_INGEST_BATCH_SIZE=${POLLER_METRICS_INGEST_BATCH_SIZE}
POLLER_METRICS_INGEST_BATCH_SIZE=${POLLER_METRICS_INGEST_BATCH_SIZE:-2000}

# The next two comma delimited lists work in concet with each other and must align
CONFIG_DATA_TYPES="${CONFIG_DATA_TYPES}"
CONFIG_FILES="${CONFIG_FILES}"
if [[ "$CONFIG_FILES" == "" ]]; then
    # attempt to create the CONFIG_DATA_TYPES and CONFIG_FILES by scanning the config directory
    for config_file in ../../config/*.xml; do
        CONFIG_DATA_TYPE=`grep -A 1 -B 1 '>data.name<' $config_file | grep '<value>' | sed 's/.*<value>//' | sed 's/<\/value>.*//' | sed 's/\.//'`
        if [[ "$CONFIG_DATA_TYPE" != "" ]]; then
            CONFIG_DATA_TYPES=$CONFIG_DATA_TYPE,$CONFIG_DATA_TYPES
            CONFIG_FILES=${config_file##*/},$CONFIG_FILES
        fi
    done
fi

FIVE_MIN_MAP_OUTPUT_COMPRESS=${FIVE_MIN_MAP_OUTPUT_COMPRESS}
FIVE_MIN_MAP_OUTPUT_COMPRESS=${FIVE_MIN_MAP_OUTPUT_COMPRESS:-true}
FIVE_MIN_MAP_OUTPUT_COMPRESSION_CODEC=${FIVE_MIN_MAP_OUTPUT_COMPRESSION_CODEC}
FIVE_MIN_MAP_OUTPUT_COMPRESSION_CODEC=${FIVE_MIN_MAP_OUTPUT_COMPRESSION_CODEC:-org.apache.hadoop.io.compress.DefaultCodec}
FIVE_MIN_MAP_OUTPUT_COMPRESSION_TYPE=${FIVE_MIN_MAP_OUTPUT_COMPRESSION_TYPE}
FIVE_MIN_MAP_OUTPUT_COMPRESSION_TYPE=${FIVE_MIN_MAP_OUTPUT_COMPRESSION_TYPE:-RECORD}

FIFTEEN_MIN_MAP_OUTPUT_COMPRESS=${FIFTEEN_MIN_MAP_OUTPUT_COMPRESS}
FIFTEEN_MIN_MAP_OUTPUT_COMPRESS=${FIFTEEN_MIN_MAP_OUTPUT_COMPRESS:-true}
FIFTEEN_MIN_MAP_OUTPUT_COMPRESSION_CODEC=${FIFTEEN_MIN_MAP_OUTPUT_COMPRESSION_CODEC}
FIFTEEN_MIN_MAP_OUTPUT_COMPRESSION_CODEC=${FIFTEEN_MIN_MAP_OUTPUT_COMPRESSION_CODEC:-org.apache.hadoop.io.compress.DefaultCodec}
FIFTEEN_MIN_MAP_OUTPUT_COMPRESSION_TYPE=${FIFTEEN_MIN_MAP_OUTPUT_COMPRESSION_TYPE}
FIFTEEN_MIN_MAP_OUTPUT_COMPRESSION_TYPE=${FIFTEEN_MIN_MAP_OUTPUT_COMPRESSION_TYPE:-RECORD}

ONE_HR_MAP_OUTPUT_COMPRESS=${ONE_HR_MAP_OUTPUT_COMPRESS}
ONE_HR_MAP_OUTPUT_COMPRESS=${ONE_HR_MAP_OUTPUT_COMPRESS:-true}
ONE_HR_MAP_OUTPUT_COMPRESSION_CODEC=${ONE_HR_MAP_OUTPUT_COMPRESSION_CODEC}
ONE_HR_MAP_OUTPUT_COMPRESSION_CODEC=${ONE_HR_MAP_OUTPUT_COMPRESSION_CODEC:-org.apache.hadoop.io.compress.DefaultCodec}
ONE_HR_MAP_OUTPUT_COMPRESSION_TYPE=${ONE_HR_MAP_OUTPUT_COMPRESSION_TYPE}
ONE_HR_MAP_OUTPUT_COMPRESSION_TYPE=${ONE_HR_MAP_OUTPUT_COMPRESSION_TYPE:-BLOCK}

BULK_INGEST_DATA_TYPES="${BULK_INGEST_DATA_TYPES}"
LIVE_INGEST_DATA_TYPES="${LIVE_INGEST_DATA_TYPES}"
MISSION_MGMT_DATA_TYPES="${MISSION_MGMT_DATA_TYPES}"

BULK_INGEST_REDUCERS="${BULK_INGEST_REDUCERS}"
LIVE_INGEST_REDUCERS="${LIVE_INGEST_REDUCERS}"

declare -i INGEST_BULK_MAPPERS=${INGEST_BULK_MAPPERS}
declare -i INGEST_MAX_BULK_BLOCKS_PER_JOB=${INGEST_MAX_BULK_BLOCKS_PER_JOB}
declare -i INGEST_LIVE_MAPPERS=${INGEST_LIVE_MAPPERS}
declare -i INGEST_MAX_LIVE_BLOCKS_PER_JOB=${INGEST_MAX_LIVE_BLOCKS_PER_JOB}

declare -i POLLER_THREADS=${POLLER_THREADS}

MAP_LOADER_HDFS_NAME_NODES="${MAP_LOADER_HDFS_NAME_NODES}"
MAP_LOADER_HDFS_NAME_NODES="${MAP_LOADER_HDFS_NAME_NODES:-$WAREHOUSE_HDFS_NAME_NODE}"
NUM_MAP_LOADERS="${NUM_MAP_LOADERS}"
NUM_MAP_LOADERS="${NUM_MAP_LOADERS:-1}"

ZOOKEEPER_HOME="${ZOOKEEPER_HOME}"

JAVA_HOME="${JAVA_HOME}"
PYTHON="${PYTHON}"

HDFS_BASE_DIR="${HDFS_BASE_DIR}"

BASE_WORK_DIR="${BASE_WORK_DIR}"
BASE_WORK_DIR="${BASE_WORK_DIR:-/data/Ingest}"

HDFS_MONITOR_ARGS="${HDFS_MONITOR_ARGS}"

MONITOR_SERVER_HOST="${MONITOR_SERVER_HOST}"
MONITOR_ENABLED="${MONITOR_ENABLED}"
MONITOR_ENABLED="${MONITOR_ENABLED:-true}"

LOG_DIR="${LOG_DIR}"
FLAG_DIR="${FLAG_DIR}"
BIN_DIR_FOR_FLAGS="${BIN_DIR_FOR_FLAGS}"
FLAG_MAKER_CONFIG="${FLAG_MAKER_CONFIG}"

declare -i NUM_SHARDS=${NUM_SHARDS}
declare -i NUM_DATE_INDEX_SHARDS=${NUM_DATE_INDEX_SHARDS}

SHARD_TABLE_NAME="${SHARD_TABLE_NAME}"
SHARD_TABLE_NAME="${SHARD_TABLE_NAME:-shard}"
SHARD_INDEX_TABLE_NAME="${SHARD_INDEX_TABLE_NAME}"
SHARD_INDEX_TABLE_NAME="${SHARD_INDEX_TABLE_NAME:-shardIndex}"
SHARD_REVERSE_INDEX_TABLE_NAME="${SHARD_REVERSE_INDEX_TABLE_NAME}"
SHARD_REVERSE_INDEX_TABLE_NAME="${SHARD_REVERSE_INDEX_TABLE_NAME:-shardReverseIndex}"
KNOWLEDGE_TABLE_NAME="${KNOWLEDGE_TABLE_NAME}"
KNOWLEDGE_TABLE_NAME="${KNOWLEDGE_TABLE_NAME:-knowledge}"
KNOWLEDGE_METADATA_TABLE_NAME="${KNOWLEDGE_METADATA_TABLE_NAME}"
KNOWLEDGE_METADATA_TABLE_NAME="${KNOWLEDGE_METADATA_TABLE_NAME:-knowledgeMetadata}"
KNOWLEDGE_SHARD_TABLE_NAME="${KNOWLEDGE_SHARD_TABLE_NAME}"
KNOWLEDGE_SHARD_TABLE_NAME="${KNOWLEDGE_SHARD_TABLE_NAME:-knowledgeShard}"
KNOWLEDGE_SHARD_INDEX_TABLE_NAME="${KNOWLEDGE_SHARD_INDEX_TABLE_NAME}"
KNOWLEDGE_SHARD_INDEX_TABLE_NAME="${KNOWLEDGE_SHARD_INDEX_TABLE_NAME:-knowledgeIndex}"
KNOWLEDGE_SHARD_REVERSE_INDEX_TABLE_NAME="${KNOWLEDGE_SHARD_REVERSE_INDEX_TABLE_NAME}"
KNOWLEDGE_SHARD_REVERSE_INDEX_TABLE_NAME="${KNOWLEDGE_SHARD_REVERSE_INDEX_TABLE_NAME:-knowledgeReverseIndex}"
METADATA_TABLE_NAME="${METADATA_TABLE_NAME}"
METADATA_TABLE_NAME="${METADATA_TABLE_NAME:-DatawaveMetadata}"
UUID_INDEX_TABLE_NAME="${UUID_INDEX_TABLE_NAME}"
UUID_INDEX_TABLE_NAME="${UUID_INDEX_TABLE_NAME:-uuidIndex}"
EDGE_TABLE_NAME="${EDGE_TABLE_NAME}"
EDGE_TABLE_NAME="${EDGE_TABLE_NAME:-edge}"
PROTOBUF_EDGE_TABLE_NAME="${PROTOBUF_EDGE_TABLE_NAME}"
PROTOBUF_EDGE_TABLE_NAME="${PROTOBUF_EDGE_TABLE_NAME:-protobufedge}"
ERROR_METADATA_TABLE_NAME="${ERROR_METADATA_TABLE_NAME}"
ERROR_METADATA_TABLE_NAME="${ERROR_METADATA_TABLE_NAME:-errorMetadata}"
ERROR_SHARD_TABLE_NAME="${ERROR_SHARD_TABLE_NAME}"
ERROR_SHARD_TABLE_NAME="${ERROR_SHARD_TABLE_NAME:-errorShard}"
ERROR_SHARD_INDEX_TABLE_NAME="${ERROR_SHARD_INDEX_TABLE_NAME}"
ERROR_SHARD_INDEX_TABLE_NAME="${ERROR_SHARD_INDEX_TABLE_NAME:-errorIndex}"
ERROR_SHARD_REVERSE_INDEX_TABLE_NAME="${ERROR_SHARD_REVERSE_INDEX_TABLE_NAME}"
ERROR_SHARD_REVERSE_INDEX_TABLE_NAME="${ERROR_SHARD_REVERSE_INDEX_TABLE_NAME:-errorReverseIndex}"
PROCESSING_ERRORS_TABLE_NAME="${PROCESSING_ERRORS_TABLE_NAME}"
PROCESSING_ERRORS_TABLE_NAME="${PROCESSING_ERRORS_TABLE_NAME:-processingErrors}"
ALL_PAIRS_INDEX_TABLE_NAME="${ALL_PAIRS_INDEX_TABLE_NAME}"
ALL_PAIRS_INDEX_TABLE_NAME="${ALL_PAIRS_INDEX_TABLE_NAME:-allPairsIndex}"
QUERY_METRICS_BASE_NAME="${QUERY_METRICS_BASE_NAME}"
QUERY_METRICS_BASE_NAME="${QUERY_METRICS_BASE_NAME:-QueryMetrics}"


MAP_LOADER_MAJC_THRESHOLD="${MAP_LOADER_MAJC_THRESHOLD}"
MAP_LOADER_MAJC_THRESHOLD="${MAP_LOADER_MAJC_THRESHOLD:-32000}"

# using ../ingest instead of ./ allows scripts in other bin directories to use this
findVersion (){
  ls -1 $1/$2-*.jar | grep -v sources | grep -v javadoc | sort | tail -1 | sed 's/.*\///' | sed "s/$2-//" | sed 's/.jar//'
}
findHadoopVersion (){
  $1/bin/hadoop version | head -1 | awk '{print $2}'
}
METRICS_VERSION=$(findVersion ../../lib datawave-metrics-core)
INGEST_VERSION=$(findVersion ../../lib datawave-ingest-csv)
ZOOKEEPER_VERSION=$(findVersion $ZOOKEEPER_HOME zookeeper)
HADOOP_VERSION=$(findHadoopVersion $INGEST_HADOOP_HOME)


# Turn some of the comma delimited lists into arrays
OLD_IFS="$IFS"
IFS=","
STAGING_HOSTS=( $STAGING_HOSTS )
POLLER_DATA_TYPES=( $POLLER_DATA_TYPES )
POLLER_INPUT_DIRECTORIES=( $POLLER_INPUT_DIRECTORIES )
POLLER_OUTPUT_DIRECTORIES=( $POLLER_OUTPUT_DIRECTORIES )
POLLER_CLIENT_OPTS=( $POLLER_CLIENT_OPTS )
FLAG_MAKER_CONFIG=( $FLAG_MAKER_CONFIG )
CONFIG_DATA_TYPES=( $CONFIG_DATA_TYPES )
CONFIG_FILES=( $CONFIG_FILES )
MAP_LOADER_HDFS_NAME_NODES=( $MAP_LOADER_HDFS_NAME_NODES )
NUM_MAP_LOADERS=( $NUM_MAP_LOADERS )
IFS="$OLD_IFS"

# We need commas in some poller client opts, but that's also the IFS
# for making the array above.  So where we need commas, use ;; instead
# and replace that in the array with , again.
POLLER_CLIENT_OPTS=( "${POLLER_CLIENT_OPTS[@]//;;/,}" )

# add the POLLER_CLIENT_EXTRA_OPTS to every poller
for (( count=0; count < ${#POLLER_DATA_TYPES[@]}; count=$((count + 1)) )); do
  POLLER_CLIENT_OPTS[$count]="$POLLER_CLIENT_EXTRA_OPTS ${POLLER_CLIENT_OPTS[$count]}"
done

# Export the variables as needed (required by some python scripts and some java code)
export LOG_DIR
export FLAG_DIR
export BIN_DIR_FOR_FLAGS
export INGEST_HDFS_NAME_NODE HDFS_BASE_DIR BASE_WORK_DIR
export INGEST_BULK_MAPPERS INGEST_MAX_BULK_BLOCKS_PER_JOB
export INGEST_LIVE_MAPPERS INGEST_MAX_LIVE_BLOCKS_PER_JOB
export BULK_INGEST_REDUCERS LIVE_INGEST_REDUCERS MISSION_MGMT_INGEST_REDUCERS
export BULK_INGEST_DATA_TYPES LIVE_INGEST_DATA_TYPES MISSION_MGMT_DATA_TYPES
export MONITOR_SERVER_HOST MONITOR_ENABLED
export PYTHON INGEST_HADOOP_HOME WAREHOUSE_HADOOP_HOME JAVA_HOME
export NUM_SHARDS NUM_DATE_INDEX_SHARDS
export INDEX_STATS_MAX_MAPPERS

ONE_HR_CHILD_MAP_MAX_MEMORY_MB="${ONE_HR_CHILD_MAP_MAX_MEMORY_MB}"
ONE_HR_CHILD_MAP_MAX_MEMORY_MB="${ONE_HR_CHILD_MAP_MAX_MEMORY_MB:-$BULK_CHILD_MAX_MEMORY_MB}"
FIFTEEN_MIN_CHILD_MAP_MAX_MEMORY_MB="${FIFTEEN_MIN_CHILD_MAP_MAX_MEMORY_MB}"
FIFTEEN_MIN_CHILD_MAP_MAX_MEMORY_MB="${FIFTEEN_MIN_CHILD_MAP_MAX_MEMORY_MB:-$LIVE_CHILD_MAX_MEMORY_MB}"
FIVE_MIN_CHILD_MAP_MAX_MEMORY_MB="${FIVE_MIN_CHILD_MAP_MAX_MEMORY_MB}"
FIVE_MIN_CHILD_MAP_MAX_MEMORY_MB="${FIVE_MIN_CHILD_MAP_MAX_MEMORY_MB:-$LIVE_CHILD_MAX_MEMORY_MB}"

ONE_HR_CHILD_REDUCE_MAX_MEMORY_MB="${ONE_HR_CHILD_REDUCE_MAX_MEMORY_MB}"
ONE_HR_CHILD_REDUCE_MAX_MEMORY_MB="${ONE_HR_CHILD_REDUCE_MAX_MEMORY_MB:-$BULK_CHILD_MAX_MEMORY_MB}"
FIFTEEN_MIN_CHILD_REDUCE_MAX_MEMORY_MB="${FIFTEEN_MIN_CHILD_REDUCE_MAX_MEMORY_MB}"
FIFTEEN_MIN_CHILD_REDUCE_MAX_MEMORY_MB="${FIFTEEN_MIN_CHILD_REDUCE_MAX_MEMORY_MB:-$LIVE_CHILD_MAX_MEMORY_MB}"
FIVE_MIN_CHILD_REDUCE_MAX_MEMORY_MB="${FIVE_MIN_CHILD_REDUCE_MAX_MEMORY_MB}"
FIVE_MIN_CHILD_REDUCE_MAX_MEMORY_MB="${FIVE_MIN_CHILD_REDUCE_MAX_MEMORY_MB:-$LIVE_CHILD_MAX_MEMORY_MB}"

DEFAULT_IO_SORT_MB="${DEFAULT_IO_SORT_MB}"
DEFAULT_IO_SORT_MB="${DEFAULT_IO_SORT_MB:-"768"}"
ONE_HR_CHILD_IO_SORT_MB="${ONE_HR_CHILD_IO_SORT_MB}"
ONE_HR_CHILD_IO_SORT_MB="${ONE_HR_CHILD_IO_SORT_MB:-$DEFAULT_IO_SORT_MB}"
FIFTEEN_MIN_CHILD_IO_SORT_MB="${FIFTEEN_MIN_CHILD_IO_SORT_MB}"
FIFTEEN_MIN_CHILD_IO_SORT_MB="${FIFTEEN_MIN_CHILD_IO_SORT_MB:-$DEFAULT_IO_SORT_MB}"
FIVE_MIN_CHILD_IO_SORT_MB="${FIVE_MIN_CHILD_IO_SORT_MB}"
FIVE_MIN_CHILD_IO_SORT_MB="${FIVE_MIN_CHILD_IO_SORT_MB:-$DEFAULT_IO_SORT_MB}"

POLLER_ONE_HR_FILE_BLOCK_SIZE_MB="${POLLER_ONE_HR_FILE_BLOCK_SIZE_MB}"
POLLER_ONE_HR_FILE_BLOCK_SIZE_MB="${POLLER_ONE_HR_FILE_BLOCK_SIZE_MB:-$POLLER_BULK_FILE_BLOCK_SIZE_MB}"
POLLER_FIFTEEN_MIN_FILE_BLOCK_SIZE_MB="${POLLER_FIFTEEN_MIN_FILE_BLOCK_SIZE_MB}"
POLLER_FIVE_MIN_FILE_BLOCK_SIZE_MB="${POLLER_FIVE_MIN_FILE_BLOCK_SIZE_MB}"
POLLER_FIVE_MIN_FILE_BLOCK_SIZE_MB="${POLLER_FIVE_MIN_FILE_BLOCK_SIZE_MB:-$POLLER_LIVE_FILE_BLOCK_SIZE_MB}"

POLLER_ONE_HR_MAX_OUTPUT_RECORDS="${POLLER_ONE_HR_MAX_OUTPUT_RECORDS}"
POLLER_ONE_HR_MAX_OUTPUT_RECORDS="${POLLER_ONE_HR_MAX_OUTPUT_RECORDS:-$POLLER_LIVE_MAX_OUTPUT_RECORDS}"
POLLER_FIFTEEN_MIN_MAX_OUTPUT_RECORDS="${POLLER_FIFTEEN_MIN_MAX_OUTPUT_RECORDS}"
POLLER_FIVE_MIN_MAX_OUTPUT_RECORDS="${POLLER_FIVE_MIN_MAX_OUTPUT_RECORDS}"
POLLER_FIVE_MIN_MAX_OUTPUT_RECORDS="${POLLER_FIVE_MIN_MAX_OUTPUT_RECORDS:-$POLLER_BULK_MAX_OUTPUT_RECORDS}"

POLLER_ONE_HR_MAX_TOTAL_EVENT_OUTPUT_MB="${POLLER_ONE_HR_MAX_TOTAL_EVENT_OUTPUT_MB}"
POLLER_ONE_HR_MAX_TOTAL_EVENT_OUTPUT_MB="${POLLER_ONE_HR_MAX_TOTAL_EVENT_OUTPUT_MB:-$POLLER_BULK_MAX_TOTAL_EVENT_OUTPUT_MB}"
POLLER_FIFTEEN_MIN_MAX_TOTAL_EVENT_OUTPUT_MB="${POLLER_FIFTEEN_MIN_MAX_TOTAL_EVENT_OUTPUT_MB}"
POLLER_FIVE_MIN_MAX_TOTAL_EVENT_OUTPUT_MB="${POLLER_FIVE_MIN_MAX_TOTAL_EVENT_OUTPUT_MB}"
POLLER_FIVE_MIN_MAX_TOTAL_EVENT_OUTPUT_MB="${POLLER_FIVE_MIN_MAX_TOTAL_EVENT_OUTPUT_MB:-$POLLER_LIVE_MAX_TOTAL_EVENT_OUTPUT_MB}"

ONE_HR_INGEST_DATA_TYPES="${ONE_HR_INGEST_DATA_TYPES}"
ONE_HR_INGEST_DATA_TYPES="${ONE_HR_INGEST_DATA_TYPES:-$BULK_INGEST_DATA_TYPES}"
FIFTEEN_MIN_INGEST_DATA_TYPES="${FIFTEEN_MIN_INGEST_DATA_TYPES}"
FIVE_MIN_INGEST_DATA_TYPES="${FIVE_MIN_INGEST_DATA_TYPES}"
FIVE_MIN_INGEST_DATA_TYPES="${FIVE_MIN_INGEST_DATA_TYPES:-$LIVE_INGEST_DATA_TYPES}"
COMPOSITE_INGEST_DATA_TYPES="${COMPOSITE_INGEST_DATA_TYPES}"
DEPRECATED_INGEST_DATA_TYPES="${DEPRECATED_INGEST_DATA_TYPES}"

ONE_HR_INGEST_REDUCERS="${ONE_HR_INGEST_REDUCERS}"
ONE_HR_INGEST_REDUCERS="${ONE_HR_INGEST_REDUCERS:-$BULK_INGEST_REDUCERS}"
FIFTEEN_MIN_INGEST_REDUCERS="${FIFTEEN_MIN_INGEST_REDUCERS}"
FIVE_MIN_INGEST_REDUCERS="${FIVE_MIN_INGEST_REDUCERS}"
FIVE_MIN_INGEST_REDUCERS="${FIVE_MIN_INGEST_REDUCERS:-$LIVE_INGEST_REDUCERS}"

ONE_HR_INGEST_GROUPING="${ONE_HR_INGEST_GROUPING}"
ONE_HR_INGEST_GROUPING="${ONE_HR_INGEST_GROUPING:-none}"
FIFTEEN_MIN_INGEST_GROUPING="${FIFTEEN_MIN_INGEST_GROUPING}"
FIFTEEN_MIN_INGEST_GROUPING="${FIFTEEN_MIN_INGEST_GROUPING:-none}"
FIVE_MIN_INGEST_GROUPING="${FIVE_MIN_INGEST_GROUPING}"
FIVE_MIN_INGEST_GROUPING="${FIVE_MIN_INGEST_GROUPING:-none}"

# The number of jobs to run concurrently.
# Note with bulk jobs and the marker mechanism, then number of concurrent jobs could be twice this setting
INGEST_ONE_HR_JOBS="${INGEST_ONE_HR_JOBS}"
INGEST_ONE_HR_JOBS="${INGEST_ONE_HR_JOBS:-$INGEST_BULK_JOBS}"
INGEST_FIFTEEN_MIN_JOBS="${INGEST_FIFTEEN_MIN_JOBS}"
INGEST_FIFTEEN_MIN_JOBS="${INGEST_FIFTEEN_MIN_JOBS:-$INGEST_LIVE_JOBS}"
INGEST_FIVE_MIN_JOBS="${INGEST_FIVE_MIN_JOBS}"
INGEST_FIVE_MIN_JOBS="${INGEST_FIVE_MIN_JOBS:-$INGEST_LIVE_JOBS}"


ONE_HR_INGEST_TIMEOUT_SECS="${ONE_HR_INGEST_TIMEOUT_SECS}"
ONE_HR_INGEST_TIMEOUT_SECS="${ONE_HR_INGEST_TIMEOUT_SECS:-300}"
FIFTEEN_MIN_INGEST_TIMEOUT_SECS="${FIFTEEN_MIN_INGEST_TIMEOUT_SECS}"
FIFTEEN_MIN_INGEST_TIMEOUT_SECS="${FIFTEEN_MIN_INGEST_TIMEOUT_SECS:-45}"
FIVE_MIN_INGEST_TIMEOUT_SECS="${FIVE_MIN_INGEST_TIMEOUT_SECS}"
FIVE_MIN_INGEST_TIMEOUT_SECS="${FIVE_MIN_INGEST_TIMEOUT_SECS:-10}"

INGEST_ONE_HR_MAPPERS=${INGEST_ONE_HR_MAPPERS}
declare -i INGEST_ONE_HR_MAPPERS=${INGEST_ONE_HR_MAPPERS:-$INGEST_BULK_MAPPERS}
declare -i INGEST_FIFTEEN_MIN_MAPPERS=${INGEST_FIFTEEN_MIN_MAPPERS}
INGEST_FIVE_MIN_MAPPERS=${INGEST_FIVE_MIN_MAPPERS}
declare -i INGEST_FIVE_MIN_MAPPERS=${INGEST_FIVE_MIN_MAPPERS:-$INGEST_LIVE_MAPPERS}

declare -i INDEX_STATS_MAX_MAPPERS=${INDEX_STATS_MAX_MAPPERS}

INGEST_MAX_ONE_HR_BLOCKS_PER_JOB=${INGEST_MAX_ONE_HR_BLOCKS_PER_JOB}
declare -i INGEST_MAX_ONE_HR_BLOCKS_PER_JOB=${INGEST_MAX_ONE_HR_BLOCKS_PER_JOB:-$INGEST_MAX_BULK_BLOCKS_PER_JOB}
declare -i INGEST_MAX_FIFTEEN_MIN_BLOCKS_PER_JOB=${INGEST_MAX_FIFTEEN_MIN_BLOCKS_PER_JOB}
INGEST_MAX_FIVE_MIN_BLOCKS_PER_JOB=${INGEST_MAX_FIVE_MIN_BLOCKS_PER_JOB}
declare -i INGEST_MAX_FIVE_MIN_BLOCKS_PER_JOB=${INGEST_MAX_FIVE_MIN_BLOCKS_PER_JOB:-$INGEST_MAX_LIVE_BLOCKS_PER_JOB}
# Export the variables as needed (required by some python scripts and some java code)
export INGEST_ONE_HR_JOBS
export INGEST_FIFTEEN_MIN_JOBS
export INGEST_FIVE_MIN_JOBS
export INGEST_ONE_HR_MAPPERS INGEST_MAX_ONE_HR_BLOCKS_PER_JOB
export INGEST_FIFTEEN_MIN_MAPPERS INGEST_MAX_FIFTEEN_MIN_BLOCKS_PER_JOB
export INGEST_FIVE_MIN_MAPPERS INGEST_MAX_FIVE_MIN_BLOCKS_PER_JOB
export ONE_HR_INGEST_REDUCERS FIFTEEN_MIN_INGEST_REDUCERS FIVE_MIN_INGEST_REDUCERS
export ONE_HR_INGEST_GROUPING FIFTEEN_MIN_INGEST_GROUPING FIVE_MIN_INGEST_GROUPING
export ONE_HR_INGEST_DATA_TYPES FIFTEEN_MIN_INGEST_DATA_TYPES FIVE_MIN_INGEST_DATA_TYPES
export ONE_HR_INGEST_TIMEOUT_SECS FIFTEEN_MIN_INGEST_TIMEOUT_SECS FIVE_MIN_INGEST_TIMEOUT_SECS

# AGEOFF ENVIRONMENT VARIABLES
AGEOFF_SERVER_CERT="${HOME}/certificates/${server.cert.basename}.pem"

# CERT required for various download scripts (PEM format)
export SERVER_CERT="${SERVER_CERT}"
export KEYSTORE="${KEYSTORE}"
export KEYSTORE_TYPE="${KEYSTORE_TYPE}"
export TRUSTSTORE="${TRUSTSTORE}"
export TRUSTSTORE_TYPE="${TRUSTSTORE_TYPE}"


# CACHE VARIABLES
HORNETQ_HOST=${hornetq.host}
HORNETQ_PORT=${hornetq.port}
CACHE_USER=${cache.accumulo.username}
CACHE_KEEPERS=${cache.accumulo.zookeepers}
CACHE_INSTANCE=${cache.accumulo.instance}

LOAD_JOBCACHE_CPU_MULTIPLIER="${LOAD_JOBCACHE_CPU_MULTIPLIER}"
declare -i LOAD_JOBCACHE_CPU_MULTIPLIER=${LOAD_JOBCACHE_CPU_MULTIPLIER:-2}

# some functions used by script to parse flag file names

isNumber() {
  re='^[0-9]+$'
  if [[ $1 =~ $re ]]; then
    echo "true"
  else
    echo "false"
  fi
}

flagPipeline() {
  BASENAME=${1%.*}
  PIPELINE=${BASENAME##*.}
  if [[ $(isNumber $PIPELINE) == "true" ]]; then
    echo $PIPELINE
  else
    echo 0
  fi
}

flagBasename() {
  f=$1
  BASENAME=${f%%.flag*}
  echo $BASENAME
}

