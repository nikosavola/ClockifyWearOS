package fi.nikosavola.clockifywear.appfunctions

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionSerializable

// The shapes an on-device agent sees. Kept deliberately flat and string-typed rather than exposing
// the Clockify DTOs, so a change to Clockify's wire format cannot silently change the contract an
// agent was described.
//
// Every field a call may legitimately have nothing to say about is nullable with a default, because
// an agent reading a description cannot tell "absent" from "not applicable" otherwise.

/** The timer running right now, or `isRunning = false` when nothing is running. */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionSerializable(isDescribedByKDoc = true)
data class RunningTimer(
  val isRunning: Boolean,
  val projectName: String? = null,
  val description: String? = null,
  val elapsedMinutes: Long? = null,
)

/** One active Clockify project the user can track time against. */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ProjectSummary(val id: String, val name: String, val clientName: String? = null)

/** One entry the user tracked before, newest first. */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionSerializable(isDescribedByKDoc = true)
data class RecentTimeEntry(
  val projectName: String? = null,
  val description: String? = null,
  /** Null when the entry is still running, which is not the same as it having taken no time. */
  val durationMinutes: Long? = null,
)
