#!/usr/bin/env bash
set -e

APP_NAME=kafka-transform
# extract version from sbt
VERSION=$(sbt -Dsbt.log.noformat=true "show version" | awk '/./{line=$0} END{print line}' | cut -d' ' -f2)

# Check if the image we're trying to build isn't already available.
if [[ ! -z $(docker images -q "socialmetrix/$APP_NAME:$VERSION") ]]; then
  echo "The image $APP_NAME:$VERSION already exists."
  echo "Building same image will cause dangling images."
  echo "If you need a new image, please make sure to bump the version."
  exit 1
fi

sbt clean assembly

echo "Building ..."
docker build -t socialmetrix/$APP_NAME:$VERSION -t socialmetrix/$APP_NAME .
echo
echo
echo "Pushing to the Repository"
docker push socialmetrix/$APP_NAME:$VERSION
docker push socialmetrix/$APP_NAME:latest