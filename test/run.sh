#!/usr/bin/env bash

set -e

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

###########################################################################
##### BEGIN CONFIG
###########################################################################

TEST_DIR=$SCRIPT_DIR/${TEST:-maven-100-artifacts/virtual}

# MVN_IMAGE=maven:3.9.11-eclipse-temurin-21-alpine
# 3.8.7-eclipse-temurin-17-alpine
# 3.8.8-eclipse-temurin-21-alpine

MVN_ACTION=dependency:go-offline

MVN_FLAGS=""  #-Dmaven.artifact.threads=40 -Daether.dependencyCollector.impl=bf"

RUN_DIR=${RUN_DIR:-$SCRIPT_DIR/../target/tests/$(date +%s)}

###########################################################################
##### END CONFIG
###########################################################################

function run_test() {
    run_dir=$1
    repo_dir=$2
    cmd="mvn $MVN_ACTION -B -Dmaven.test.skip=true $MVN_FLAGS"
    if [[ -n "$MVN_IMAGE" ]]; then
        if [[ -n "$GOOGLE_APPLICATION_CREDENTIALS" ]]; then
            creds="-v $GOOGLE_APPLICATION_CREDENTIALS:/root/.config/gcloud/application_default_credentials.json:ro"
            creds="$creds -e GOOGLE_APPLICATION_CREDENTIALS=/root/.config/gcloud/application_default_credentials.json"
        else
            creds="-v $HOME/.config:/root/.config:ro" 
        fi
        cmd="docker run --rm \
            -v $run_dir:/src \
            $creds \
            -w /src \
            $MVN_IMAGE \
            $cmd -Dmaven.repo.local=/src/$(basename $repo_dir)"
    else
        cd $run_dir
        cmd="$cmd -Dmaven.repo.local=$repo_dir"
    fi

    echo "RUNNING $cmd"
    $cmd | tee -a "$run_dir"/test.log
}

mkdir -p "$RUN_DIR"
repo_dir="$RUN_DIR"/_repo

(cd $SCRIPT_DIR/.. && ./gradlew publishToMavenLocal -Dmaven.repo.local="$repo_dir")

cp -rf $TEST_DIR/. "$RUN_DIR"
run_test "$RUN_DIR" "$repo_dir"
