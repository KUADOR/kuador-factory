package ec.kuador.board.attachment.domain;

import java.io.InputStream;

/** Puerto: almacenamiento de archivos (compatible con S3). */
public interface ObjectStorage {

    /** Está configurado en este servidor. */
    boolean available();

    void put(String key, String contentType, InputStream content, long size);

    /** Abre el archivo para leerlo; quien llama lo cierra. Si ya no existe, lanza {@code NotFoundException}. */
    InputStream get(String key);

    void delete(String key);
}
