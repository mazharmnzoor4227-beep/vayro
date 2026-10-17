package com.vayro.app.network

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

data class GitHubAccount(val login: String, val avatarUrl: String)

data class GitHubRepository(
    val fullName: String,
    val cloneUrl: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
    val ownerLogin: String = "",
    val ownerType: String = "",
)

data class GitHubIssue(
    val number: Int,
    val title: String,
    val state: String,
    val user: String,
    val comments: Int,
    val htmlUrl: String,
    val createdAt: String = "",
    val body: String = "",
)

data class GitHubPullRequest(
    val number: Int,
    val title: String,
    val state: String,
    val user: String,
    val headBranch: String,
    val baseBranch: String,
    val htmlUrl: String,
    val body: String = "",
)

sealed interface GitHubTokenPoll {
    data class Success(val accessToken: String) : GitHubTokenPoll
    data class Pending(val slowDown: Boolean = false) : GitHubTokenPoll
    data class Failure(val message: String) : GitHubTokenPoll
}

open class GitHubClient {
    fun requestDeviceCode(clientId: String): GitHubDeviceCode {
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf("client_id" to clientId),
        )
        return GitHubDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            expiresInSeconds = json.optLong("expires_in", 900L),
            intervalSeconds = json.optLong("interval", 5L).coerceAtLeast(5L),
        )
    }

    fun pollDeviceToken(clientId: String, deviceCode: String): GitHubTokenPoll {
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to clientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        json.optString("access_token").takeIf(String::isNotBlank)?.let { return GitHubTokenPoll.Success(it) }
        return when (val error = json.optString("error")) {
            "authorization_pending" -> GitHubTokenPoll.Pending()
            "slow_down" -> GitHubTokenPoll.Pending(slowDown = true)
            "access_denied" -> GitHubTokenPoll.Failure("GitHub authorization was cancelled")
            "expired_token" -> GitHubTokenPoll.Failure("The GitHub sign-in code expired. Try again.")
            else -> GitHubTokenPoll.Failure(json.optString("error_description").ifBlank { error.ifBlank { "GitHub sign-in failed" } })
        }
    }

    fun account(token: String): GitHubAccount {
        val json = getJson("https://api.github.com/user", token) as JSONObject
        return GitHubAccount(json.getString("login"), json.optString("avatar_url"))
    }

    fun repositories(token: String): List<GitHubRepository> {
        val result = LinkedHashMap<String, GitHubRepository>()
        var page = 1
        while (page <= 10) {
            val array = getJson(
                "https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page=$page",
                token,
            ) as JSONArray
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val owner = item.optJSONObject("owner")
                val repo = GitHubRepository(
                    fullName = item.getString("full_name"),
                    cloneUrl = item.getString("clone_url"),
                    private = item.optBoolean("private"),
                    defaultBranch = item.optString("default_branch", "main"),
                    description = item.optString("description"),
                    updatedAt = item.optString("updated_at"),
                    ownerLogin = owner?.optString("login").orEmpty(),
                    ownerType = owner?.optString("type").orEmpty(),
                )
                result[repo.fullName] = repo
            }
            if (array.length() < 100) break
            page++
        }
        return result.values.toList()
    }

    /**
     * QA Medium: list endpoints used to fetch a single page (30/20 items)
     * and silently truncate. Pages are now followed at per_page=100 up to
     * [maxPages] (500 items), stopping early on a short page — the same
     * loop repositories() already used. Bare-array endpoints and the
     * `workflow_runs` wrapper shape are both handled.
     */
    private fun fetchAllPages(baseUrl: String, token: String, maxPages: Int = 5): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var page = 1
        while (page <= maxPages) {
            val separator = if ('?' in baseUrl) "&" else "?"
            val response = getJson("$baseUrl${separator}per_page=100&page=$page", token)
            val array = when (response) {
                is JSONArray -> response
                is JSONObject -> response.optJSONArray("workflow_runs") ?: JSONArray()
                else -> JSONArray()
            }
            for (i in 0 until array.length()) items += array.getJSONObject(i)
            if (array.length() < 100) break
            page++
        }
        return items
    }

    fun issues(token: String, repoFullName: String): List<GitHubIssue> {
        val list = mutableListOf<GitHubIssue>()
        for (item in fetchAllPages("https://api.github.com/repos/$repoFullName/issues?state=all", token)) {
            if (!item.has("pull_request")) {
                list.add(
                    GitHubIssue(
                        number = item.getInt("number"),
                        title = item.getString("title"),
                        state = item.getString("state"),
                        user = item.optJSONObject("user")?.optString("login") ?: "user",
                        comments = item.optInt("comments", 0),
                        htmlUrl = item.optString("html_url", ""),
                        createdAt = item.optString("created_at", ""),
                        body = item.optString("body", ""),
                    )
                )
            }
        }
        return list
    }

    fun createIssue(token: String, repoFullName: String, title: String, body: String = ""): GitHubIssue {
        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/issues", token, payload) as JSONObject
        return GitHubIssue(
            number = json.getInt("number"),
            title = json.getString("title"),
            state = json.getString("state"),
            user = json.optJSONObject("user")?.optString("login") ?: "user",
            comments = 0,
            htmlUrl = json.getString("html_url"),
            createdAt = json.optString("created_at", ""),
            body = json.optString("body", ""),
        )
    }

    open fun createIssueComment(token: String, repoFullName: String, issueNumber: Int, comment: String): GitHubIssueComment {
        val payload = JSONObject().apply { put("body", comment) }
        val json = postJson("https://api.github.com/repos/$repoFullName/issues/$issueNumber/comments", token, payload) as JSONObject
        return GitHubIssueComment(
            id = json.getLong("id"),
            body = json.getString("body"),
            user = json.optJSONObject("user")?.optString("login") ?: "user",
            createdAt = json.optString("created_at", "")
        )
    }

    open fun closeIssue(token: String, repoFullName: String, issueNumber: Int): Boolean {
        val payload = JSONObject().apply { put("state", "closed") }
        val res = patchJson("https://api.github.com/repos/$repoFullName/issues/$issueNumber", token, payload)
        return res is JSONObject
    }

    fun pullRequests(token: String, repoFullName: String): List<GitHubPullRequest> {
        val list = mutableListOf<GitHubPullRequest>()
        for (item in fetchAllPages("https://api.github.com/repos/$repoFullName/pulls?state=all", token)) {
            list.add(
                GitHubPullRequest(
                    number = item.getInt("number"),
                    title = item.getString("title"),
                    state = item.getString("state"),
                    user = item.optJSONObject("user")?.optString("login") ?: "user",
                    headBranch = item.optJSONObject("head")?.optString("ref") ?: "",
                    baseBranch = item.optJSONObject("base")?.optString("ref") ?: "",
                    htmlUrl = item.optString("html_url", ""),
                    body = item.optString("body", ""),
                )
            )
        }
        return list
    }

    open fun createPullRequest(
        token: String,
        repoFullName: String,
        title: String,
        head: String,
        base: String,
        body: String = "",
        draft: Boolean = false,
    ): GitHubPullRequest {
        val payload = JSONObject().apply {
            put("title", title)
            put("head", head)
            put("base", base)
            put("body", body)
            put("draft", draft)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/pulls", token, payload) as JSONObject
        return GitHubPullRequest(
            number = json.getInt("number"),
            title = json.getString("title"),
            state = json.getString("state"),
            user = json.optJSONObject("user")?.optString("login") ?: "user",
            headBranch = json.optJSONObject("head")?.optString("ref") ?: head,
            baseBranch = json.optJSONObject("base")?.optString("ref") ?: base,
            htmlUrl = json.getString("html_url"),
            body = json.optString("body", body),
        )
    }

    open fun mergePullRequest(
        token: String,
        repoFullName: String,
        prNumber: Int,
        commitTitle: String? = null,
        commitMessage: String? = null,
        method: PullRequestMergeMethod = PullRequestMergeMethod.SQUASH,
    ): MergeResult {
        val payload = JSONObject().apply {
            put("merge_method", method.value)
            if (!commitTitle.isNullOrBlank()) put("commit_title", commitTitle)
            if (!commitMessage.isNullOrBlank()) put("commit_message", commitMessage)
        }
        val res = putJson("https://api.github.com/repos/$repoFullName/pulls/$prNumber/merge", token, payload) as JSONObject
        return MergeResult(
            merged = res.optBoolean("merged", false),
            message = res.optString("message", ""),
            sha = res.optString("sha", null)
        )
    }

    fun getPullRequestDiff(token: String, repoFullName: String, prNumber: Int): String {
        val connection = (URL("https://api.github.com/repos/$repoFullName/pulls/$prNumber").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github.v3.diff")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "Kodex-Android")
        }
        try {
            // Check the status BEFORE touching inputStream: on a 403/404/
            // rate-limit response, inputStream throws a bare
            // FileNotFoundException that hides the real GitHub error.
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error(githubErrorMessage(status, errorBody))
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun submitReview(
        token: String,
        repoFullName: String,
        prNumber: Int,
        event: String,
        body: String
    ): GitHubReview {
        val payload = JSONObject().apply {
            put("event", event)
            put("body", body)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/pulls/$prNumber/reviews", token, payload) as JSONObject
        return GitHubReview(
            id = json.getLong("id"),
            user = json.optJSONObject("user")?.optString("login") ?: "reviewer",
            state = json.optString("state", event),
            body = json.optString("body", body),
            submittedAt = json.optString("submitted_at", "")
        )
    }

    open fun listReviews(token: String, repoFullName: String, prNumber: Int): List<GitHubReview> {
        val array = getJson("https://api.github.com/repos/$repoFullName/pulls/$prNumber/reviews", token) as? JSONArray ?: return emptyList()
        val list = mutableListOf<GitHubReview>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list += GitHubReview(
                id = item.getLong("id"),
                user = item.optJSONObject("user")?.optString("login") ?: "reviewer",
                state = item.optString("state", ""),
                body = item.optString("body", ""),
                submittedAt = item.optString("submitted_at", "")
            )
        }
        return list
    }

    fun listWorkflows(token: String, repoFullName: String): List<GitHubWorkflow> {
        val json = getJson("https://api.github.com/repos/$repoFullName/actions/workflows", token) as? JSONObject ?: return emptyList()
        val array = json.optJSONArray("workflows") ?: return emptyList()
        val list = mutableListOf<GitHubWorkflow>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list += GitHubWorkflow(
                id = item.getLong("id"),
                name = item.getString("name"),
                path = item.getString("path"),
                state = item.optString("state", "active")
            )
        }
        return list
    }

    fun listWorkflowRuns(token: String, repoFullName: String): List<GitHubWorkflowRun> {
        val list = mutableListOf<GitHubWorkflowRun>()
        for (item in fetchAllPages("https://api.github.com/repos/$repoFullName/actions/runs", token)) {
            list += GitHubWorkflowRun(
                id = item.getLong("id"),
                name = item.optString("name", "CI Run"),
                status = item.getString("status"),
                conclusion = item.optString("conclusion").takeIf { it.isNotBlank() },
                htmlUrl = item.getString("html_url"),
                headBranch = item.optString("head_branch", "main"),
                event = item.optString("event", "push"),
                createdAt = item.optString("created_at", ""),
                runNumber = item.optInt("run_number", 1)
            )
        }
        return list
    }

    open fun dispatchWorkflow(
        token: String,
        repoFullName: String,
        workflowId: String,
        ref: String = "main",
        inputs: Map<String, Any> = emptyMap()
    ): Boolean {
        val payload = JSONObject().apply {
            put("ref", ref)
            if (inputs.isNotEmpty()) put("inputs", JSONObject(inputs))
        }
        val res = postJson("https://api.github.com/repos/$repoFullName/actions/workflows/$workflowId/dispatches", token, payload)
        return res != null
    }

    fun listReleases(token: String, repoFullName: String): List<GitHubRelease> {
        val list = mutableListOf<GitHubRelease>()
        for (item in fetchAllPages("https://api.github.com/repos/$repoFullName/releases", token)) {
            val assetsArray = item.optJSONArray("assets")
            val assets = mutableListOf<GitHubReleaseAsset>()
            if (assetsArray != null) {
                for (j in 0 until assetsArray.length()) {
                    val a = assetsArray.getJSONObject(j)
                    assets += GitHubReleaseAsset(
                        id = a.getLong("id"),
                        name = a.getString("name"),
                        sizeBytes = a.optLong("size", 0L),
                        downloadUrl = a.optString("browser_download_url", ""),
                        contentType = a.optString("content_type", "")
                    )
                }
            }
            list += GitHubRelease(
                id = item.getLong("id"),
                tagName = item.getString("tag_name"),
                name = item.optString("name", item.getString("tag_name")),
                body = item.optString("body", ""),
                draft = item.optBoolean("draft", false),
                prerelease = item.optBoolean("prerelease", false),
                createdAt = item.optString("created_at", ""),
                publishedAt = item.optString("published_at", ""),
                htmlUrl = item.optString("html_url", ""),
                assets = assets,
                uploadUrl = item.optString("upload_url", "")
            )
        }
        return list
    }

    open fun createRelease(
        token: String,
        repoFullName: String,
        tagName: String,
        name: String = tagName,
        body: String = "",
        draft: Boolean = false,
        prerelease: Boolean = false
    ): GitHubRelease {
        val payload = JSONObject().apply {
            put("tag_name", tagName)
            put("name", name)
            put("body", body)
            put("draft", draft)
            put("prerelease", prerelease)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/releases", token, payload) as JSONObject
        return GitHubRelease(
            id = json.getLong("id"),
            tagName = json.getString("tag_name"),
            name = json.optString("name", tagName),
            body = json.optString("body", body),
            draft = json.optBoolean("draft", draft),
            prerelease = json.optBoolean("prerelease", prerelease),
            createdAt = json.optString("created_at", ""),
            publishedAt = json.optString("published_at", ""),
            htmlUrl = json.getString("html_url"),
            assets = emptyList(),
            uploadUrl = json.optString("upload_url", "")
        )
    }

    /**
     * Milestone 9 — GitHub automation remainder.
     *
     * PR detail view for automation decisions (merge gating). `mergeable` is null
     * while GitHub is still computing it; callers must treat null as unknown.
     */
    open fun getPullRequest(token: String, repoFullName: String, prNumber: Int): GitHubPullRequestDetail {
        val json = getJson("https://api.github.com/repos/$repoFullName/pulls/$prNumber", token) as JSONObject
        return GitHubPullRequestDetail(
            number = json.getInt("number"),
            title = json.getString("title"),
            state = json.getString("state"),
            user = json.optJSONObject("user")?.optString("login") ?: "user",
            headBranch = json.optJSONObject("head")?.optString("ref") ?: "",
            baseBranch = json.optJSONObject("base")?.optString("ref") ?: "",
            headSha = json.optJSONObject("head")?.optString("sha") ?: "",
            htmlUrl = json.optString("html_url", ""),
            body = json.optString("body", ""),
            draft = json.optBoolean("draft", false),
            merged = json.optBoolean("merged", false),
            mergeable = if (json.isNull("mergeable")) null else json.optBoolean("mergeable"),
            mergeableState = json.optString("mergeable_state", "unknown"),
        )
    }

    open fun updatePullRequest(
        token: String,
        repoFullName: String,
        prNumber: Int,
        title: String? = null,
        body: String? = null,
    ): GitHubPullRequest {
        val payload = JSONObject().apply {
            if (title != null) put("title", title)
            if (body != null) put("body", body)
        }
        val json = patchJson("https://api.github.com/repos/$repoFullName/pulls/$prNumber", token, payload) as JSONObject
        return GitHubPullRequest(
            number = json.getInt("number"),
            title = json.getString("title"),
            state = json.getString("state"),
            user = json.optJSONObject("user")?.optString("login") ?: "user",
            headBranch = json.optJSONObject("head")?.optString("ref") ?: "",
            baseBranch = json.optJSONObject("base")?.optString("ref") ?: "",
            htmlUrl = json.optString("html_url", ""),
            body = json.optString("body", ""),
        )
    }

    open fun requestReviewers(token: String, repoFullName: String, prNumber: Int, reviewers: List<String>): Boolean {
        if (reviewers.isEmpty()) return true
        val payload = JSONObject().apply { put("reviewers", JSONArray(reviewers)) }
        val res = postJson("https://api.github.com/repos/$repoFullName/pulls/$prNumber/requested_reviewers", token, payload)
        return res is JSONObject
    }

    /** Combined commit status rollup for a ref (branch, tag, or SHA) — used for merge gating. */
    open fun getCombinedStatus(token: String, repoFullName: String, ref: String): CombinedStatus {
        val encoded = URLEncoder.encode(ref, "UTF-8")
        val json = getJson("https://api.github.com/repos/$repoFullName/commits/$encoded/status", token) as JSONObject
        return CombinedStatus(
            state = json.optString("state", "pending"),
            totalCount = json.optInt("total_count", 0),
            commitSha = json.optString("sha", ""),
        )
    }

    open fun addLabels(token: String, repoFullName: String, issueNumber: Int, labels: List<String>): List<GitHubLabel> {
        if (labels.isEmpty()) return emptyList()
        val payload = JSONObject().apply { put("labels", JSONArray(labels)) }
        val array = postJson("https://api.github.com/repos/$repoFullName/issues/$issueNumber/labels", token, payload) as? JSONArray
            ?: return emptyList()
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            GitHubLabel(
                name = item.getString("name"),
                color = item.optString("color", ""),
                description = item.optString("description", ""),
            )
        }
    }

    open fun removeLabel(token: String, repoFullName: String, issueNumber: Int, label: String): Boolean {
        val encoded = URLEncoder.encode(label, "UTF-8")
        return deleteRequest("https://api.github.com/repos/$repoFullName/issues/$issueNumber/labels/$encoded", token)
    }

    open fun assignIssue(token: String, repoFullName: String, issueNumber: Int, assignees: List<String>): Boolean {
        if (assignees.isEmpty()) return true
        val payload = JSONObject().apply { put("assignees", JSONArray(assignees)) }
        val res = postJson("https://api.github.com/repos/$repoFullName/issues/$issueNumber/assignees", token, payload)
        return res is JSONObject
    }

    open fun updateIssue(
        token: String,
        repoFullName: String,
        issueNumber: Int,
        title: String? = null,
        body: String? = null,
    ): Boolean {
        val payload = JSONObject().apply {
            if (title != null) put("title", title)
            if (body != null) put("body", body)
        }
        val res = patchJson("https://api.github.com/repos/$repoFullName/issues/$issueNumber", token, payload)
        return res is JSONObject
    }

    open fun listIssueComments(token: String, repoFullName: String, issueNumber: Int): List<GitHubIssueComment> {
        val array = getJson(
            "https://api.github.com/repos/$repoFullName/issues/$issueNumber/comments?per_page=100",
            token,
        ) as? JSONArray ?: return emptyList()
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            GitHubIssueComment(
                id = item.getLong("id"),
                body = item.getString("body"),
                user = item.optJSONObject("user")?.optString("login") ?: "user",
                createdAt = item.optString("created_at", ""),
            )
        }
    }

    /** Creates a git ref (e.g. `refs/tags/v1.2.3`) pointing at a commit SHA. Returns the ref name. */
    open fun createGitRef(token: String, repoFullName: String, ref: String, sha: String): String {
        val payload = JSONObject().apply {
            put("ref", ref)
            put("sha", sha)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/git/refs", token, payload) as JSONObject
        return json.optString("ref", ref)
    }

    open fun generateReleaseNotes(
        token: String,
        repoFullName: String,
        tagName: String,
        previousTagName: String? = null,
        targetCommitish: String? = null,
    ): String {
        val payload = JSONObject().apply {
            put("tag_name", tagName)
            if (!previousTagName.isNullOrBlank()) put("previous_tag_name", previousTagName)
            if (!targetCommitish.isNullOrBlank()) put("target_commitish", targetCommitish)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/releases/generate-notes", token, payload) as JSONObject
        return json.optString("body", "")
    }

    /**
     * Uploads a release asset. `uploadUrlTemplate` is the release's `upload_url`
     * (e.g. `https://uploads.github.com/.../assets{?name,label}`). Content is streamed
     * through `contentStream` so the caller never holds the whole file in memory
     * and tests can fake this without touching disk.
     */
    open fun uploadReleaseAsset(
        uploadUrlTemplate: String,
        fileName: String,
        contentStream: () -> java.io.InputStream,
        contentLength: Long,
        contentType: String,
        token: String,
    ): GitHubReleaseAsset {
        val base = uploadUrlTemplate.substringBefore("{")
        val endpoint = "$base?name=${URLEncoder.encode(fileName, "UTF-8")}"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", contentType)
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "Kodex-Android")
            setFixedLengthStreamingMode(contentLength)
        }
        contentStream().use { input ->
            connection.outputStream.use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        val json = readResponse(connection) as JSONObject
        return GitHubReleaseAsset(
            id = json.getLong("id"),
            name = json.getString("name"),
            sizeBytes = json.optLong("size", contentLength),
            downloadUrl = json.optString("browser_download_url", ""),
            contentType = json.optString("content_type", contentType),
        )
    }

    open fun deleteRelease(token: String, repoFullName: String, releaseId: Long): Boolean {
        return deleteRequest("https://api.github.com/repos/$repoFullName/releases/$releaseId", token)
    }

    open fun getWorkflowRun(token: String, repoFullName: String, runId: Long): GitHubWorkflowRun {
        val json = getJson("https://api.github.com/repos/$repoFullName/actions/runs/$runId", token) as JSONObject
        return GitHubWorkflowRun(
            id = json.getLong("id"),
            name = json.optString("name", "CI Run"),
            status = json.getString("status"),
            conclusion = json.optString("conclusion").takeIf { it.isNotBlank() },
            htmlUrl = json.getString("html_url"),
            headBranch = json.optString("head_branch", ""),
            event = json.optString("event", ""),
            createdAt = json.optString("created_at", ""),
            runNumber = json.optInt("run_number", 0),
        )
    }

    open fun listWorkflowRunsForWorkflow(token: String, repoFullName: String, workflowId: String): List<GitHubWorkflowRun> {
        return fetchAllPages(
            "https://api.github.com/repos/$repoFullName/actions/workflows/$workflowId/runs",
            token,
        ).map { item ->
            GitHubWorkflowRun(
                id = item.getLong("id"),
                name = item.optString("name", "CI Run"),
                status = item.getString("status"),
                conclusion = item.optString("conclusion").takeIf { it.isNotBlank() },
                htmlUrl = item.getString("html_url"),
                headBranch = item.optString("head_branch", ""),
                event = item.optString("event", ""),
                createdAt = item.optString("created_at", ""),
                runNumber = item.optInt("run_number", 0),
            )
        }
    }

    open fun cancelWorkflowRun(token: String, repoFullName: String, runId: Long): Boolean {
        val res = postJson("https://api.github.com/repos/$repoFullName/actions/runs/$runId/cancel", token, JSONObject())
        return res != null
    }

    open fun rerunWorkflow(token: String, repoFullName: String, runId: Long): Boolean {
        val res = postJson("https://api.github.com/repos/$repoFullName/actions/runs/$runId/rerun", token, JSONObject())
        return res != null
    }

    private fun deleteRequest(endpoint: String, token: String): Boolean {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "Kodex-Android")
        }
        val status = connection.responseCode
        // Drain the stream so the connection can be reused; 204 carries no body.
        try {
            (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) { /* best effort */ }
        if (status !in 200..299) {
            error("GitHub returned HTTP $status")
        }
        return true
    }

    private fun postForm(endpoint: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "Kodex-Android")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection) as JSONObject
    }

    private fun postJson(endpoint: String, token: String, payload: JSONObject): Any {
        val body = payload.toString().toByteArray()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "Kodex-Android")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    private fun putJson(endpoint: String, token: String, payload: JSONObject): Any {
        val body = payload.toString().toByteArray()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "Kodex-Android")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    private fun patchJson(endpoint: String, token: String, payload: JSONObject): Any {
        val body = payload.toString().toByteArray()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "Kodex-Android")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    // QA Medium: protected-open (was private) so pagination can be
    // unit-tested with a fake fetcher instead of live GitHub calls.
    protected open fun getJson(endpoint: String, token: String): Any {
        // QA Medium: one transient 429/5xx used to be final. Retry with
        // backoff, honoring the server's Retry-After header.
        var attempt = 0
        while (true) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
                setRequestProperty("User-Agent", "Kodex-Android")
            }
            val code = connection.responseCode
            if (HttpRetry.isRetryable(code) && attempt < HttpRetry.MAX_ATTEMPTS - 1) {
                val retryAfter = connection.getHeaderField("Retry-After")
                connection.disconnect()
                HttpRetry.sleepBeforeRetry(attempt, retryAfter)
                attempt++
                continue
            }
            return readResponse(connection)
        }
    }

    private fun readResponse(connection: HttpURLConnection): Any {
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            error(githubErrorMessage(status, text))
        }
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }
}

/**
 * QA H16: the error message for a failed GitHub call, shared by the JSON
 * response path and the PR-diff path. Prefers GitHub's own JSON `message` ("Not Found", "API rate limit
 * exceeded…") and falls back to the bare HTTP status when the body is
 * empty or is not JSON. Extracted (not inlined twice) so the mapping is
 * unit-tested once.
 */
internal fun githubErrorMessage(status: Int, errorBody: String): String {
    val message = runCatching { JSONObject(errorBody).optString("message") }.getOrNull()
    return message?.takeIf(String::isNotBlank) ?: "GitHub returned HTTP $status"
}
