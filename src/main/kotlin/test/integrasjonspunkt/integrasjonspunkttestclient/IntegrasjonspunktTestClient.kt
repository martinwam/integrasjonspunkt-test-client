package test.integrasjonspunkt.integrasjonspunkttestclient

import com.jayway.jsonpath.JsonPath
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.zip.ZipInputStream


data class IpError(val timestamp: String,
				   val status: String,
				   val error: String,
				   val exception: String,
				   override val message: String,
				   val path: String) : Throwable()

val url: String = System.getenv("ip-url") ?: "http://localhost:9093"
val orgnr: String = System.getenv("orgnr") ?: "910076787"
val retries: Long = System.getenv("retries")?.toLong() ?: 10L
val timeout: Long = System.getenv("timeout")?.toLong() ?: 30L
val attachment: String = System.getProperty("attachment") ?: "test.txt"

fun main(args: Array<String>) {

	val arkivmelding = File("data/arkivmelding.xml")
			.readText()
			.replace("{{attachment}}", attachment)
	val attachmentFile = File("data/$attachment")
	val sbd = File("data/sbd.json")
			.readText()
			.replace("{{orgnr}}", orgnr)
			.replace("{{creationTime}}", LocalDateTime.now().toString())
			.replace("{{ttl}}", LocalDateTime.now().plusDays(1L).toString())

	val wc = WebClient.builder()
			.baseUrl(url)
			.build()

	val requestBuilder = MultipartBodyBuilder()
	requestBuilder.part("sbd", sbd)
	requestBuilder.part("arkivmelding.xml", ByteArrayResource(arkivmelding.toByteArray()))
			.header("Content-Disposition", "form-data; name=arkivmelding.xml; filename=arkivmelding.xml")
			.header("Content-Type: application/xml")
	requestBuilder.part(attachment, FileSystemResource(attachmentFile))
	val request = requestBuilder.build()

	// send message
	val postResponse = wc.post().uri("/api/messages/out/multipart")
			.bodyValue(request)
			.retrieve()
			.onStatus(HttpStatus::isError) { r ->
				r.bodyToMono(IpError::class.java).flatMap {
					Mono.error<Throwable>(it)
				}
			}
			.bodyToMono(String::class.java)
	val sbdResponse = postResponse.block(Duration.ofSeconds(3L)) ?: throw RuntimeException("null response from POST")
	println("Response from POST:\n$sbdResponse")
	val messageId = JsonPath.parse(sbdResponse).read("$.standardBusinessDocumentHeader.documentIdentification.instanceIdentifier", String::class.java)
	println("Message sent. Id: $messageId")

	// peek
	print("Attempting to peek the message..")
	val peek = wc.get().uri { it.path("/api/messages/in/peek").queryParam("messageId", messageId).build() }
			.exchange()
			.flatMap { r ->
				if (r.statusCode().isError || r.statusCode() == HttpStatus.NO_CONTENT) {
					r.createException().flatMap { Mono.error<Throwable>(it) }
				} else {
					r.bodyToMono(String::class.java)
				}
			}
	try {
		peek.retryWhen(Retry.fixedDelay(retries, Duration.ofSeconds(2L))
				.doAfterRetry { print(".") })
				.block(Duration.ofSeconds(timeout)) ?: throw RuntimeException("peek returned empty")
	} catch (e: Exception) {
		println("\nFailed to fetch the message after $retries retries: ${e.cause?.message ?: e.message}")
		return
	}
	println("\nMessage locked.")

	// pop asic
    println("Popping ASiC..")
	val asicStream = wc.get().uri("/api/messages/in/pop/$messageId")
			.retrieve()
			.bodyToMono(DataBuffer::class.java)
			.block(Duration.ofSeconds(3L)) ?: throw java.lang.RuntimeException("ASiC stream returned null")
	ZipInputStream(asicStream.asInputStream()).use { zip ->
        var entry = zip.nextEntry
		while (entry != null) {
			if (entry.name == attachment) {
				println("Content of $attachment from ASiC: ${String(zip.readBytes())}")
			}
			entry = zip.nextEntry
		}
	}
	println("ASiC read.")

	// delete
	wc.delete().uri("/api/messages/in/$messageId")
			.retrieve()
			.toBodilessEntity()
			.block(Duration.ofSeconds(3L))
    println("Deleted message.")

}
