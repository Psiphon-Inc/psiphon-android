echo "running single test to find stacktrace if track down JNI loading error"
mvn clean test -Dtest=RandomTest#testProducesDifferentDefaultRandomBytes -X
