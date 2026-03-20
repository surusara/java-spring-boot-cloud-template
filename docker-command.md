docker-compose up -d



docker ps
# You should see kafka and zookeeper containers

# Or test connection:
docker-compose logs kafka | Select-String "started"


docker exec 215e5554ae29 kafka-topics --create `
  --topic payments.input `
  --partitions 1 `
  --replication-factor 1 `
  --bootstrap-server localhost:9092

docker exec 215e5554ae29 kafka-topics --create `
  --topic payments.output `
  --partitions 1 `
  --replication-factor 1 `
  --bootstrap-server localhost:9092

  docker exec 215e5554ae29 kafka-topics --list --bootstrap-server localhost:9092
# Output: payments.input, payments.output


cd C:\Users\chhay\Downloads\kafka-streams-cb-project\kafka-streams-cb
java -jar target/kafka-streams-cb-1.0.0.jar `
  --spring.kafka.bootstrap-servers=localhost:9092 `
  --server.port=8080