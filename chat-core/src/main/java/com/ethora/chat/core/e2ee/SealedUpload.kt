package com.ethora.chat.core.e2ee

import java.io.File

/**
 * Sealing of an attachment for an encrypted room, as the composer does it (web
 * `useSendMessage` -> `sealFileForUpload`): what goes up is opaque bytes under a random name
 * with a neutral mimetype, so the server never sees the file, its type or its filename. The
 * per-file key material travels in the OMEMO-encrypted `<body>` of the media stanza.
 */
object SealedUpload {
    /** Mimetype the server is told for every sealed file. */
    const val SEALED_MIMETYPE = "application/octet-stream"

    /** [file] holds the ciphertext under a random, extension-free name; [keyMaterial] stays on the device and in the stanza body. */
    class Result(val file: File, val keyMaterial: String)

    /**
     * Reads [source], seals it and writes the ciphertext into [outDir]. The caller uploads
     * [Result.file] (with `clientEncrypted`) and deletes it afterwards.
     */
    fun seal(source: File, mimetype: String, originalName: String, outDir: File): Result {
        val sealed = OmemoFileEnvelope.seal(source.readBytes(), mimetype, originalName)
        outDir.mkdirs()
        val out = File(outDir, sealed.filename)
        out.writeBytes(sealed.ciphertext)
        return Result(out, sealed.keyMaterial)
    }
}
