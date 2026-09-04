package fi.nikosavola.clockifywear.companion

import java.util.UUID

// Wire contract for phone-companion sign-in, shared by both apps as a real Gradle module (not
// duplicated files) so the two sides can never drift out of sync - see CompanionSignInErrorCode
// for the other half of the contract.
//
// Every request carries a caller-generated request id as a path suffix, and every reply echoes it
// back the same way. Without this, a slow reply to an abandoned/timed-out attempt could be
// mistaken for the response to a later retry - there is nothing else identifying which attempt a
// given message belongs to.

/** Matches the `android_wear_capabilities` entry in `wear/src/play/res/values/wear.xml`. */
const val WATCH_CAPABILITY = "clockify_wear_app"

private const val API_KEY_REQUEST_PATH_PREFIX = "/clockify/api-key-request/"
private const val SIGN_IN_SUCCESS_PATH_PREFIX = "/clockify/sign-in-success/"
private const val SIGN_IN_FAILURE_PATH_PREFIX = "/clockify/sign-in-failure/"

fun newRequestId(): String = UUID.randomUUID().toString()

fun apiKeyRequestPath(requestId: String): String = API_KEY_REQUEST_PATH_PREFIX + requestId

fun signInSuccessPath(requestId: String): String = SIGN_IN_SUCCESS_PATH_PREFIX + requestId

fun signInFailurePath(requestId: String): String = SIGN_IN_FAILURE_PATH_PREFIX + requestId

fun requestIdFromApiKeyRequestPath(path: String): String? =
  path.removePrefixOrNull(API_KEY_REQUEST_PATH_PREFIX)

fun requestIdFromSignInSuccessPath(path: String): String? =
  path.removePrefixOrNull(SIGN_IN_SUCCESS_PATH_PREFIX)

fun requestIdFromSignInFailurePath(path: String): String? =
  path.removePrefixOrNull(SIGN_IN_FAILURE_PATH_PREFIX)

private fun String.removePrefixOrNull(prefix: String): String? =
  if (startsWith(prefix)) removePrefix(prefix) else null
