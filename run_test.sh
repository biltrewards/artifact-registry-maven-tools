#!/usr/bin/env bash

set -e

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

###########################################################################
##### BEGIN CONFIG
###########################################################################

TEST_DIR=$1

REPO_URL=${REPO_URL:-artifactregistry://us-maven.pkg.dev/single-scholar-280421/bilt-private-and-maven-central}

# MVN_IMAGE=maven:3.9.11-eclipse-temurin-21-alpine
# 3.8.7-eclipse-temurin-17-alpine
# 3.8.8-eclipse-temurin-21-alpine

MVN_ACTION=dependency:go-offline

MVN_FLAGS=""  #-Dmaven.artifact.threads=40 -Daether.dependencyCollector.impl=bf"

RUN_DIR=${RUN_DIR:-$SCRIPT_DIR/target/tests/$(date +%s.%6N)}

EXTENSIONS_XML="
<extensions xmlns='http://maven.apache.org/EXTENSIONS/1.0.0' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'
  xsi:schemaLocation='http://maven.apache.org/EXTENSIONS/1.0.0 http://maven.apache.org/xsd/core-extensions-1.0.0.xsd'>
  <extension>
    <groupId>com.google.cloud.artifactregistry</groupId>
    <artifactId>artifactregistry-maven-wagon</artifactId>
    <version>2.2.6-SNAPSHOT</version>
  </extension>
</extensions>
"

# SETTINGS_XML="
# <?xml version='1.0' encoding='UTF-8'?>
# <settings xmlns='http://maven.apache.org/SETTINGS/1.0.0'
#           xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'
#           xsi:schemaLocation='http://maven.apache.org/SETTINGS/1.0.0 
#                               http://maven.apache.org/xsd/settings-1.0.0.xsd'>

# </settings>
# "

SETTINGS_XML="
<?xml version='1.0' encoding='UTF-8'?>
<settings xmlns='http://maven.apache.org/SETTINGS/1.0.0'
          xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'
          xsi:schemaLocation='http://maven.apache.org/SETTINGS/1.0.0 
                              http://maven.apache.org/xsd/settings-1.0.0.xsd'>

  <profiles>
    <profile>
      <id>custom-repos</id>
      <repositories>
        <repository>
          <id>custom</id>
          <url>$REPO_URL</url>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>custom</id>
          <url>$REPO_URL</url>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>

  <activeProfiles>
    <activeProfile>custom-repos</activeProfile>
  </activeProfiles>
</settings>
"

# SETTINGS_XML="
# <?xml version='1.0' encoding='UTF-8'?>
# <settings xmlns='http://maven.apache.org/SETTINGS/1.0.0'
#           xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'
#           xsi:schemaLocation='http://maven.apache.org/SETTINGS/1.0.0 
#                               http://maven.apache.org/xsd/settings-1.0.0.xsd'>

#   <profiles>
#     <profile>
#       <id>custom-repos</id>
#       <repositories>
#         <repository>
#           <id>custom</id>
#           <url>$REPO_URL</url>
#         </repository>
#         <repository>
#           <id>custom2</id>
#           <url>artifactregistry://us-maven.pkg.dev/single-scholar-280421/maven-central-cache</url>
#         </repository>
#       </repositories>
#       <pluginRepositories>
#         <pluginRepository>
#           <id>custom</id>
#           <url>$REPO_URL</url>
#         </pluginRepository>
#         <pluginRepository>
#           <id>custom2</id>
#           <url>artifactregistry://us-maven.pkg.dev/single-scholar-280421/maven-central-cache</url>
#         </pluginRepository>
#       </pluginRepositories>
#     </profile>
#   </profiles>

#   <activeProfiles>
#     <activeProfile>custom-repos</activeProfile>
#   </activeProfiles>
# </settings>
# "

###########################################################################
##### END CONFIG
###########################################################################

function run_test() {
    run_dir=$1
    repo_dir=$run_dir/_repo
    cmd="mvn $MVN_ACTION -B -Dmaven.test.skip=true $MVN_FLAGS"

    touch $run_dir/settings.xml

    if [[ -n "$REPO_URL" ]]; then        
        echo "Building wagon..."
        (cd $SCRIPT_DIR && ./gradlew publishToMavenLocal -Dmaven.repo.local="$repo_dir")

        mkdir -p $run_dir/.mvn
        echo "$EXTENSIONS_XML" > $run_dir/.mvn/extensions.xml

        echo "$SETTINGS_XML" > $run_dir/settings.xml
        cmd="$cmd -s settings.xml"
    fi

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
            $cmd -Dmaven.repo.local=/src/_repo"
    else
        cd $run_dir
        cmd="$cmd -Dmaven.repo.local=$repo_dir"
    fi

    echo "RUNNING $cmd"
    $cmd | tee -a "$run_dir"/test.log
}

mkdir -p "$RUN_DIR"
cp -rf $TEST_DIR/. "$RUN_DIR"
run_test "$RUN_DIR"
