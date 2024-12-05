docker build -f Dockerfile  -t xubiao05/fluss-base:1.0.0 .

docker-compose -f docker-compose.yaml up -d 

mvn spotless:apply

# maven-jar-plugin 在编译的时候才出包
mvn clean package -DskipTests

mvn clean install -Dmaven.test.skip=true

# 官网介绍
https://alibaba.github.io/fluss-docs/docs/intro/
# 功能特性
1、实时读写：支持亚秒级的流式读写能力。
2、列式裁剪：以列存储格式存储所有的实时流数据，提升读写性能。
3、流式更新：支持大规模的数据进行实时流式更新。
4、CDC订阅：支持mysql的cdc订阅，实现从源头直接实时拉取数据。
5、实时点查：支持高性能主键的点查，可以进行多表关联的数据加工。
6、湖流一体，无缝集成Lakehouse（例如paimon，iceberg等），与Lakehouse想结合形成实时数据层和冷数据层。