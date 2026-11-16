// Written for Thoughts. License is GPLv3.
//
// Push (Firebase Cloud Messaging) without the google-services Gradle plugin: picks, out of a
// google-services.json kept outside the repository, the client of one Android application ID and
// returns the four values FirebaseOptions needs. Evaluated by app/build.gradle at configuration
// time (one call per variant) and by FirebaseConfigSelectionTest.
//
// The script's value is a closure (File json, String packageName) -> Map:
//   [on: true, appId: …, apiKey: …, projectId: …, senderId: …]
//   [on: false, reason: "…"]   (the reason never contains a value from the file)
import groovy.json.JsonSlurper

// The values go into BuildConfig string literals: anything else than these characters is refused,
// so nothing needs escaping and nothing unexpected reaches the build.
def plain = ~/[A-Za-z0-9:._\-]+/

return { File file, String packageName ->
    if (file == null || !file.isFile()) return [on: false, reason: 'no google-services.json']
    def root
    try {
        root = new JsonSlurper().parse(file)
    } catch (Exception ignored) {
        return [on: false, reason: 'google-services.json is not valid JSON']
    }
    if (!(root instanceof Map)) return [on: false, reason: 'google-services.json is not a JSON object']
    def project = root.project_info instanceof Map ? root.project_info : [:]
    def clients = root.client instanceof List ? root.client : []
    def client = clients.find { candidate ->
        candidate instanceof Map &&
            candidate.client_info instanceof Map &&
            candidate.client_info.android_client_info instanceof Map &&
            candidate.client_info.android_client_info.package_name == packageName
    }
    if (client == null) return [on: false, reason: "no client for ${packageName}"]
    def keys = client.api_key instanceof List ? client.api_key : []
    def apiKey = keys.collect { it instanceof Map ? it.current_key : null }.find { it != null && it.toString().trim() }
    def values = [
        appId    : client.client_info.mobilesdk_app_id,
        apiKey   : apiKey,
        projectId: project.project_id,
        senderId : project.project_number,
    ].collectEntries { name, value -> [name, value == null ? '' : value.toString().trim()] }
    def missing = values.findAll { name, value -> value.isEmpty() }.keySet()
    if (!missing.isEmpty()) return [on: false, reason: "the client for ${packageName} has no ${missing.join(', ')}"]
    def odd = values.findAll { name, value -> !(value ==~ plain) }.keySet()
    if (!odd.isEmpty()) return [on: false, reason: "unexpected characters in ${odd.join(', ')}"]
    return [on: true] + values
}
