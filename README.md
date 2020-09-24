# integrasjonspunkt-test-client
Send an arkivmelding message to yourself.

## Usage
Built jar:
```console
$ java -Dorgnr=123123123 -jar integrasjonspunkt-test-client-0.0.1-SNAPSHOT.jar
```
The orgnr must be the same as the running instance of integrasjonspunktet is using.
Files must be located under the data-folder relative to the working directory.

## Parameters
|Param|Default|Description|
|-----|-------|-----------|
|-Dorgnr|910076787|Orgnr of the running integrasjonspunkt instance|
|-Dip-url|http://localhost:9093|integrasjonspunkt address|
|-Dattachment|test.txt|Name of attachment file to be sent. Must be located in the data folder|
|-Dretries|10|Number of times to retry peek for incoming messages|
|-Dtimeout|30|Peek timeout, in seconds|
