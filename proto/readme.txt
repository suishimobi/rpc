create .proto

download protocol buffer compiler from:
	https://developers.google.com/protocol-buffers/docs/downloads

read readme.txt to install

run command to generate .java
	protoc -I=. --java_out=..\src\main\java .\HttpRpcProtos.proto
    
