package fi.nikosavola.clockifywear.data.api

import fi.nikosavola.clockifywear.data.api.dto.ProjectDto
import fi.nikosavola.clockifywear.data.api.dto.StartTimeEntryRequest
import fi.nikosavola.clockifywear.data.api.dto.StopTimeEntryRequest
import fi.nikosavola.clockifywear.data.api.dto.TaskDto
import fi.nikosavola.clockifywear.data.api.dto.TimeEntryDto
import fi.nikosavola.clockifywear.data.api.dto.UserDto
import fi.nikosavola.clockifywear.data.api.dto.WorkspaceDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ClockifyApi {
  @GET("user") suspend fun getCurrentUser(): UserDto

  @GET("workspaces") suspend fun getWorkspaces(): List<WorkspaceDto>

  @GET("workspaces/{workspaceId}/projects")
  suspend fun getProjects(
    @Path("workspaceId") workspaceId: String,
    @Query("archived") archived: Boolean? = null,
    @Query("page") page: Int? = null,
    @Query("page-size") pageSize: Int? = null,
  ): List<ProjectDto>

  @GET("workspaces/{workspaceId}/projects/{projectId}/tasks")
  suspend fun getProjectTasks(
    @Path("workspaceId") workspaceId: String,
    @Path("projectId") projectId: String,
    @Query("is-active") isActive: Boolean? = null,
    @Query("page") page: Int? = null,
    @Query("page-size") pageSize: Int? = null,
  ): List<TaskDto>

  @POST("workspaces/{workspaceId}/time-entries")
  suspend fun startTimeEntry(
    @Path("workspaceId") workspaceId: String,
    @Body request: StartTimeEntryRequest,
  ): TimeEntryDto

  @PATCH("workspaces/{workspaceId}/user/{userId}/time-entries")
  suspend fun stopTimeEntry(
    @Path("workspaceId") workspaceId: String,
    @Path("userId") userId: String,
    @Body request: StopTimeEntryRequest,
  ): TimeEntryDto

  @GET("workspaces/{workspaceId}/user/{userId}/time-entries")
  suspend fun getRunningTimeEntry(
    @Path("workspaceId") workspaceId: String,
    @Path("userId") userId: String,
    @Query("in-progress") inProgress: Boolean = true,
  ): List<TimeEntryDto>

  @GET("workspaces/{workspaceId}/user/{userId}/time-entries")
  suspend fun getRecentTimeEntries(
    @Path("workspaceId") workspaceId: String,
    @Path("userId") userId: String,
    @Query("page-size") pageSize: Int,
  ): List<TimeEntryDto>

  // start/end are ISO-8601 instants (e.g. Instant.toString()); Clockify treats both as UTC.
  @GET("workspaces/{workspaceId}/user/{userId}/time-entries")
  suspend fun getTimeEntries(
    @Path("workspaceId") workspaceId: String,
    @Path("userId") userId: String,
    @Query("start") start: String,
    @Query("end") end: String,
    @Query("page") page: Int,
    @Query("page-size") pageSize: Int,
  ): List<TimeEntryDto>
}
