FROM bitbucketpipelines/scala-sbt:scala-2.12 as builder
WORKDIR /app
COPY . .
RUN sbt