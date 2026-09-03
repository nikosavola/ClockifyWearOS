package fi.nikosavola.clockifywear.companion

// The byte-level half of the wire contract (CompanionProtocol.kt covers the path half): how a
// sign-in reply's payload is encoded/decoded. Before this file existed, the watch's encoder and
// the phone's decoder were two independently-maintained mirror images with no test able to prove
// they actually compose - wear and mobile are sibling modules, neither can see the other's code,
// so only a test living here can round-trip both halves.

fun encodeSignInSuccessPayload(email: String?): ByteArray =
  email.orEmpty().toByteArray(Charsets.UTF_8)

fun decodeSignInSuccessPayload(data: ByteArray): String? =
  String(data, Charsets.UTF_8).ifEmpty { null }

fun encodeSignInFailurePayload(errorCode: CompanionSignInErrorCode): ByteArray =
  errorCode.name.toByteArray(Charsets.UTF_8)

fun decodeSignInFailurePayload(data: ByteArray): CompanionSignInErrorCode =
  CompanionSignInErrorCode.fromWireValue(String(data, Charsets.UTF_8))
