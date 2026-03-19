val providedVersion = "unspecified"
val versionName = "1.0.13"

val result = if (!providedVersion.isNullOrBlank() && providedVersion != "unspecified") {
    providedVersion
} else {
    System.getenv("VERSION") ?: versionName
}

println(result)
