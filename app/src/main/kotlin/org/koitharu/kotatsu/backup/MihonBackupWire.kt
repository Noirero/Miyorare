package org.koitharu.kotatsu.backup

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Streaming reader/writer for Mihon's top-level protobuf Backup message.
 *
 * Every currently supported top-level field is length-delimited. Encoding one repeated message at
 * a time avoids materialising the complete 20k-50k library plus a second whole-backup ByteArray.
 */
internal object MihonBackupWire {

	const val FIELD_MANGA = 1
	const val FIELD_CATEGORY = 2
	const val FIELD_SOURCE = 101
	const val FIELD_PREFERENCE = 104
	const val FIELD_SOURCE_PREFERENCE = 105
	const val FIELD_EXTENSION_REPO = 106

	private const val WIRE_VARINT = 0
	private const val WIRE_FIXED64 = 1
	private const val WIRE_LENGTH_DELIMITED = 2
	private const val WIRE_FIXED32 = 5
	private const val MAX_MESSAGE_BYTES = 32 * 1024 * 1024

	private val proto = ProtoBuf

	fun <T> writeMessage(
		output: OutputStream,
		fieldNumber: Int,
		serializer: SerializationStrategy<T>,
		value: T,
	) {
		require(fieldNumber > 0)
		val payload = proto.encodeToByteArray(serializer, value)
		require(payload.size <= MAX_MESSAGE_BYTES) {
			"Mihon backup message is too large: field=$fieldNumber bytes=${payload.size}"
		}
		writeVarint(output, (fieldNumber.toLong() shl 3) or WIRE_LENGTH_DELIMITED.toLong())
		writeVarint(output, payload.size.toLong())
		output.write(payload)
	}

	fun <T> decodeMessage(payload: ByteArray, serializer: DeserializationStrategy<T>): T {
		return proto.decodeFromByteArray(serializer, payload)
	}

	/**
	 * Walks the protobuf without retaining previous fields. [onMessage] is invoked only for
	 * length-delimited fields; unknown scalar fields are skipped according to their wire type.
	 */
	suspend fun forEachMessage(input: InputStream, onMessage: suspend (fieldNumber: Int, payload: ByteArray) -> Unit) {
		while (true) {
			val key = readVarintOrNull(input) ?: return
			val fieldNumber = (key ushr 3).toInt()
			val wireType = (key and 0x7L).toInt()
			if (fieldNumber <= 0) throw IOException("Invalid protobuf field number: $fieldNumber")
			when (wireType) {
				WIRE_LENGTH_DELIMITED -> {
					val length = readVarint(input)
					if (length < 0L || length > MAX_MESSAGE_BYTES) {
						throw IOException("Mihon backup message is too large: field=$fieldNumber bytes=$length")
					}
					val payload = ByteArray(length.toInt())
					readFully(input, payload)
					onMessage(fieldNumber, payload)
				}
				WIRE_VARINT -> readVarint(input)
				WIRE_FIXED64 -> skipFully(input, 8)
				WIRE_FIXED32 -> skipFully(input, 4)
				else -> throw IOException("Unsupported protobuf wire type $wireType for field $fieldNumber")
			}
		}
	}

	private fun writeVarint(output: OutputStream, value: Long) {
		var current = value
		while (true) {
			if (current and -0x80L == 0L) {
				output.write(current.toInt())
				return
			}
			output.write(((current and 0x7fL) or 0x80L).toInt())
			current = current ushr 7
		}
	}

	private fun readVarintOrNull(input: InputStream): Long? {
		val first = input.read()
		if (first < 0) return null
		return readVarint(input, first)
	}

	private fun readVarint(input: InputStream): Long {
		val first = input.read()
		if (first < 0) throw EOFException("Unexpected EOF while reading protobuf varint")
		return readVarint(input, first)
	}

	private fun readVarint(input: InputStream, first: Int): Long {
		var result = (first and 0x7f).toLong()
		if (first and 0x80 == 0) return result
		var shift = 7
		while (shift < 64) {
			val next = input.read()
			if (next < 0) throw EOFException("Unexpected EOF while reading protobuf varint")
			result = result or ((next and 0x7f).toLong() shl shift)
			if (next and 0x80 == 0) return result
			shift += 7
		}
		throw IOException("Malformed protobuf varint")
	}

	private fun readFully(input: InputStream, target: ByteArray) {
		var offset = 0
		while (offset < target.size) {
			val read = input.read(target, offset, target.size - offset)
			if (read < 0) throw EOFException("Unexpected EOF inside protobuf message")
			offset += read
		}
	}

	private fun skipFully(input: InputStream, count: Int) {
		var remaining = count
		while (remaining > 0) {
			val skipped = input.skip(remaining.toLong()).toInt()
			if (skipped > 0) {
				remaining -= skipped
			} else {
				if (input.read() < 0) throw EOFException("Unexpected EOF while skipping protobuf field")
				remaining--
			}
		}
	}
}
