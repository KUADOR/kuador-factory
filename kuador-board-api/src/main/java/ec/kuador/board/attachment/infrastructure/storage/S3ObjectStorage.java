package ec.kuador.board.attachment.infrastructure.storage;

import ec.kuador.board.attachment.domain.ObjectStorage;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Almacenamiento compatible con S3: Garage (o MinIO, o S3). Se configura con {@code BOARD_STORAGE_S3_ENDPOINT},
 * {@code _BUCKET}, {@code _ACCESS_KEY}, {@code _SECRET_KEY} y {@code _REGION}; sin endpoint, los adjuntos quedan
 * deshabilitados. Usa rutas (no subdominios) y solo los checksums obligatorios, como espera Garage.
 */
@Component
class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client s3;
    private final String bucket;

    S3ObjectStorage(@Value("${board.storage.s3.endpoint:}") String endpoint, @Value("${board.storage.s3.region:garage}") String region,
            @Value("${board.storage.s3.bucket:kuador-adjuntos}") String bucket, @Value("${board.storage.s3.access-key:}") String accessKey,
            @Value("${board.storage.s3.secret-key:}") String secretKey) {
        this.bucket = bucket;
        if (endpoint == null || endpoint.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            this.s3 = null;
            return;
        }
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint.strip()))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey.strip(), secretKey.strip())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    /** Al arrancar avisa si el bucket no responde (no impide arrancar: el resto del tablero funciona igual). */
    @EventListener(ApplicationReadyEvent.class)
    void check() {
        if (s3 == null) {
            log.info("Adjuntos deshabilitados: falta BOARD_STORAGE_S3_ENDPOINT o sus claves.");
            return;
        }
        try {
            s3.headBucket(b -> b.bucket(bucket));
            log.info("Adjuntos en el bucket {}", bucket);
        } catch (RuntimeException e) {
            log.warn("No se pudo acceder al bucket de adjuntos {}: {}", bucket, e.getMessage());
        }
    }

    @Override
    public boolean available() {
        return s3 != null;
    }

    @Override
    public void put(String key, String contentType, InputStream content, long size) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).contentLength(size), RequestBody.fromInputStream(content, size));
    }

    @Override
    public InputStream get(String key) {
        return s3.getObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    @PreDestroy
    void close() {
        if (s3 != null) s3.close();
    }
}
