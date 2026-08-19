package ai.opencode.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shipping network policy.
 *
 * M5 permits cleartext in *debug* builds so a self-hosted OpenCode server on the
 * LAN can be reached over plain HTTP. That relaxation is scoped to a build-type
 * source set, which is an easy thing to widen by accident - moving one attribute
 * into the main config would silently ship it.
 *
 * These read the XML rather than the merged manifest because the question is
 * about what is committed, and because the two configs never coexist in one
 * build for a runtime check to compare.
 */
class NetworkSecurityConfigTest {

    /** Unit tests run with the module directory as the working directory. */
    private fun config(sourceSet: String): String {
        val candidates = listOf(
            File("src/$sourceSet/res/xml/network_security_config.xml"),
            File("app/src/$sourceSet/res/xml/network_security_config.xml"),
            File("apps/android/app/src/$sourceSet/res/xml/network_security_config.xml"),
        )
        val found = candidates.firstOrNull { it.exists() }
            ?: error("network_security_config.xml not found for '$sourceSet'; looked in ${candidates.map { it.absolutePath }}")
        return found.readText()
    }

    @Test
    fun `the release policy denies cleartext by default`() {
        val xml = config("main").withoutComments()
        assertTrue(
            "base-config must deny cleartext",
            Regex("""<base-config[^>]*cleartextTrafficPermitted\s*=\s*"false"""").containsMatchIn(xml),
        )
        assertFalse(
            "the shipping config must not permit cleartext anywhere in base-config",
            Regex("""<base-config[^>]*cleartextTrafficPermitted\s*=\s*"true"""").containsMatchIn(xml),
        )
    }

    @Test
    fun `the release policy permits cleartext only to loopback`() {
        val xml = config("main").withoutComments()
        val domains = Regex("""<domain[^>]*>([^<]+)</domain>""").findAll(xml).map { it.groupValues[1].trim() }.toList()
        assertTrue("expected loopback entries, found $domains", domains.isNotEmpty())
        // M7 reaches the on-device server here; nothing else may be added
        // without a decision, because each entry is a hole in the policy.
        assertTrue("unexpected cleartext domain in $domains", domains.all { it == "127.0.0.1" || it == "localhost" })
    }

    @Test
    fun `the release policy does not install debug trust anchors`() {
        // A custom trust anchor is how TLS verification actually gets weakened.
        // M5 deliberately does not use one, and this keeps it that way.
        val xml = config("main").withoutComments()
        assertFalse("no debug-overrides in the shipping config", xml.contains("debug-overrides"))
        assertFalse("no custom trust anchors in the shipping config", xml.contains("trust-anchors"))
    }

    @Test
    fun `the debug policy permits cleartext, and only the debug policy`() {
        // Documents the intended difference, so a reader of one file learns the
        // other exists.
        assertTrue(
            "the debug config is what allows a LAN server over http",
            Regex("""cleartextTrafficPermitted\s*=\s*"true"""").containsMatchIn(config("debug").withoutComments()),
        )
    }

    @Test
    fun `neither policy weakens certificate validation`() {
        for (sourceSet in listOf("main", "debug")) {
            val xml = config(sourceSet).withoutComments()
            assertFalse("$sourceSet must not add trust anchors", xml.contains("trust-anchors"))
        }
    }

    /** Comments explain the policy at length and would otherwise match the patterns. */
    private fun String.withoutComments() = replace(Regex("""<!--[\s\S]*?-->"""), "")
}
