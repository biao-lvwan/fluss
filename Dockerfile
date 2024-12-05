FROM ubuntu:24.04

LABEL maintainer="biao.xu@qq.com"

# 换源
RUN  echo "deb http://mirrors.aliyun.com/ubuntu/ focal main restricted universe multiverse" > /etc/apt/sources.list  \
        && echo "deb http://mirrors.aliyun.com/ubuntu/ focal-security main restricted universe multiverse" >> /etc/apt/sources.list  \
        && echo "deb http://mirrors.aliyun.com/ubuntu/ focal-updates main restricted universe multiverse" >> /etc/apt/sources.list # buildkit


RUN  apt-get update

ENV DEBIAN_FRONTEND=noninteractive
# 适配
RUN  dpkg --add-architecture i386
RUN apt-get install -y --no-install-recommends \
    build-essential \
    jq \
    libffi-dev \
    libssl-dev \
    zlib1g-dev \
    libbz2-dev \
    libreadline-dev \
    libsqlite3-dev \
    wget \
    curl \
    make \
    bash-completion \
    llvm \
    libc6-dev \
    liblzma-dev \
    tk-dev \
    uuid-dev \
    gnupg \
    git
RUN echo "source /usr/share/bash-completion/bash_completion" >> /etc/bash.bashrc # buildkit
RUN apt-get install -y openjdk-17-jre-headless
RUN apt-get install -y --force-yes libgtk2.0-0:i386 || true
# 安装maven
RUN wget https://archive.apache.org/dist/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz  \
        && tar -xzvf apache-maven-3.6.3-bin.tar.gz -C /usr/local  \
        && rm apache-maven-3.6.3-bin.tar.gz  \
        && ln -s /usr/local/apache-maven-3.6.3/bin/mvn /usr/local/bin/mvn

WORKDIR /app
CMD ["bash", "-c", "trap '' SIGINT; while true; do sleep 1; done"]