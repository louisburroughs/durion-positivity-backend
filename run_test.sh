cd ~/IdeaProjects/durion-positivity-backend
./mvnw test -pl pos-accounting -Dtest="PaymentApplicationControllerIntegrationTest,AccountingServiceIntegrationTest,DefaultGLMappingServiceTest" > ./mvn_test_output.log 2>&1
grep -A 10 "Failures:" ./mvn_test_output.log
