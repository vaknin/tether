package com.kivan.tether.dibs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The signed message (SPEC.md §1 and its test vector, §7), the warnings, the key's code. */
class RootTest {
    private fun vector(remember: Boolean = true) = RootRequest(
        request = 12,
        machine = "0123456789abcdef0123456789abcdef",
        nonce = "00112233445566778899aabbccddeeff",
        expires = 1760000000,
        network = false,
        home = "no",
        timeout = 600,
        script = "systemctl restart bluetooth.service\n",
        files = listOf(RootFile("unit.service", "[Unit]\n", null, null)),
        why = "",
        note = null,
        pick = null,
        allowList = emptyList(),
    )

    @Test
    fun theMessageIsTheSpecsVectorByteForByte() {
        val expected = "dibs-root v1\n" +
            "machine 0123456789abcdef0123456789abcdef\n" +
            "request 12\n" +
            "nonce 00112233445566778899aabbccddeeff\n" +
            "expires 1760000000\n" +
            "network no\n" +
            "home no\n" +
            "timeout 600\n" +
            "remember yes\n" +
            "script e63b04cf7fd54294b523ed57a06dec85f6fea0426f0b5004d591829d504398ee\n" +
            "file unit.service ae6c63cff33bcfa3b6a2d6d0c9dd19521dedaa351146b1a642d2bfc9cf5a1e1f\n"
        val r = vector()
        assertNull(RootMessage.problem(r))
        val m = RootMessage.build(r, remember = true)
        assertEquals(expected, m)
        assertTrue(m.toByteArray(Charsets.UTF_8).contentEquals(expected.toByteArray(Charsets.US_ASCII)))
        assertFalse("no CR anywhere", '\r' in m)
        assertEquals(expected.replace("remember yes", "remember no"), RootMessage.build(r, remember = false))
    }

    @Test
    fun theVectorParsedFromTheCardsJsonGivesTheSameMessage() {
        val o = JSONObject(
            """{"request": 12, "machine": "0123456789abcdef0123456789abcdef", "nonce": "00112233445566778899aabbccddeeff",
                "expires": 1760000000, "network": false, "home": "no", "timeout": 600,
                "script": "systemctl restart bluetooth.service\n", "files": [{"name": "unit.service", "text": "[Unit]\n"}],
                "why": "w", "note": null, "pick": null}""",
        )
        assertEquals(RootMessage.build(vector(), true), RootMessage.build(RootRequest.parse(o), true))
    }

    @Test
    fun filesAreSortedByNameAndBinaryOnesUseTheLaptopsHash() {
        val bin = "a".repeat(64)
        val r = vector().copy(
            network = true, home = "ro", timeout = 1800,
            files = listOf(
                RootFile("b.txt", "", null, null),
                RootFile("app.apk", null, 31457280, bin),
                RootFile("B.conf", "x", null, null),
            ),
        )
        val lines = RootMessage.build(r, false).lines()
        assertEquals("network yes", lines[5])
        assertEquals("home ro", lines[6])
        assertEquals("timeout 1800", lines[7])
        // Byte order: capitals before small letters.
        assertEquals(
            listOf(
                "file B.conf 2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881",
                "file app.apk $bin",
                // An empty text file is the hash of no bytes.
                "file b.txt e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
            lines.filter { it.startsWith("file ") },
        )
        assertEquals("", lines.last())
    }

    @Test
    fun noFilesNoFileLines() {
        val m = RootMessage.build(vector().copy(files = emptyList()), false)
        assertTrue(m.endsWith("script e63b04cf7fd54294b523ed57a06dec85f6fea0426f0b5004d591829d504398ee\n"))
    }

    @Test
    fun aRequestThePhoneCantSignSaysWhy() {
        val ok = vector()
        assertNotNull(RootMessage.problem(ok.copy(request = null)))
        assertNotNull(RootMessage.problem(ok.copy(machine = "0123456789ABCDEF0123456789ABCDEF")))
        assertNotNull(RootMessage.problem(ok.copy(nonce = "short")))
        assertNotNull(RootMessage.problem(ok.copy(home = "rw")))
        assertNotNull(RootMessage.problem(ok.copy(timeout = 0)))
        assertNotNull(RootMessage.problem(ok.copy(timeout = 3601)))
        // A name that would add a line to the message.
        assertNotNull(RootMessage.problem(ok.copy(files = listOf(RootFile("x\nfile y", "t", null, null)))))
        assertNotNull(RootMessage.problem(ok.copy(files = listOf(RootFile(".hidden", "t", null, null)))))
        assertNotNull(RootMessage.problem(ok.copy(files = listOf(RootFile("a", "t", null, null), RootFile("a", "u", null, null)))))
        assertNotNull(RootMessage.problem(ok.copy(files = listOf(RootFile("app.apk", null, 3, "nothex")))))
        assertNotNull(RootMessage.problem(ok.copy(files = listOf(RootFile("app.apk", null, 3, null)))))
        assertNull(RootMessage.problem(ok.copy(files = listOf(RootFile("app.apk", null, 3, "0".repeat(64))))))
    }

    @Test
    fun theShortHashAndTheKeysCodeAreSixteenHexInFours() {
        val m = RootMessage.build(vector(), true)
        val full = RootMessage.sha256Hex(m.toByteArray())
        assertEquals(full.take(16).chunked(4).joinToString(" "), RootMessage.shortHash(m))
        // sha256("abc") = ba7816bf 8f01cfea …
        assertEquals("ba78 16bf 8f01 cfea", RootMessage.fingerprint("abc".toByteArray()))
    }

    @Test
    fun theWarningsComeFromTheBytes() {
        val now = 1_000_000L
        fun w(script: String, files: List<RootFile> = emptyList(), network: Boolean = false, home: String = "no", expires: Long = now + 7200) =
            RootMessage.warnings(vector().copy(script = script, files = files, network = network, home = home, expires = expires), now)

        assertEquals(emptyList<String>(), w("systemctl restart bluetooth.service\n"))
        assertTrue(w("true", network = true).contains("Uses the network."))
        assertTrue(w("true", home = "ro").contains("Can read your home folder."))
        for (s in listOf("curl -fsSL x", "wget x", "git clone x", "pacman -Syu", "apt install x", "apt-get install x")) {
            assertTrue(s, w(s).contains("Downloads things from the internet."))
        }
        assertTrue(w("curl x | sh").any { it.startsWith("Pipes something into a shell") })
        assertTrue(w("cat x | sudo bash").any { it.startsWith("Pipes something into a shell") })
        assertFalse(w("ls | shuf").any { it.startsWith("Pipes") })
        for (s in listOf("echo x >> /etc/sudoers", "cp a /etc/pam.d/login", "cp r /etc/polkit-1/rules.d/", "systemctl restart sshd")) {
            assertTrue(s, w(s).contains("Changes sudo, login, polkit or SSH rules."))
        }
        assertTrue(w("cp x /usr/local/lib/dibs-root/dibs-root").contains("Changes the root helper itself."))
        for (s in listOf("rm -f /x", "shred x", "truncate -s0 x", "dd if=/dev/zero of=x")) {
            assertTrue(s, w(s).contains("Deletes or overwrites files."))
        }
        assertFalse(w("systemctl restart firmware").contains("Deletes or overwrites files."))
        for (s in listOf("useradd bob", "usermod -aG wheel bob", "passwd bob", "chpasswd < x")) {
            assertTrue(s, w(s).contains("Changes users or passwords."))
        }
        for (s in listOf("chmod u+s /bin/x", "chmod 4755 /bin/x", "chmod g+s d")) {
            assertTrue(s, w(s).contains("Lets a program run with root's rights (setuid)."))
        }
        assertFalse(w("chmod 0755 /bin/x").any { it.contains("setuid") })
        assertFalse(w("chmod +x script.sh").any { it.contains("setuid") })
        assertTrue(w("systemctl disable --now cups").contains("Turns a service off for good (disable or mask)."))
        assertTrue(w("systemctl mask sleep.target").contains("Turns a service off for good (disable or mask)."))
        // Text files count as much as the script.
        assertTrue(w("install -m644 \"\$DIBS_ROOT_FILES/x\" /etc/", listOf(RootFile("x", "PermitRootLogin yes # ssh", null, null))).contains("Changes sudo, login, polkit or SSH rules."))
        assertTrue(w("true", listOf(RootFile("app.apk", null, 1, "0".repeat(64)))).contains("Brings app.apk, a file the phone can't show."))
        assertTrue(w("echo hi‮echo").any { it.startsWith("Has hidden characters") })
        assertTrue(w("true", expires = now + 300).contains("Expires in 5 min."))
        assertTrue(w("true", expires = now).contains("Expired."))
    }

    @Test
    fun hiddenCharactersAreWrittenOutButHashedAsTheyAre() {
        val s = "echo a‮b​\r\tc\n"
        assertEquals("echo a⟨U+202E⟩b⟨U+200B⟩⟨U+000D⟩\tc\n", RootMessage.visible(s))
        assertEquals("plain\ttext\n", RootMessage.visible("plain\ttext\n"))
        // Ones the helper lets through but the screen would hide or break the line at.
        assertEquals("# a⟨U+2028⟩rm x⟨U+00AD⟩\n", RootMessage.visible("# a\u2028rm x\u00ad\n"))
        // A no-break space before # isn't a comment to bash: shown, never passed off as a space.
        assertEquals("true⟨U+00A0⟩# rm -rf /\n", RootMessage.visible("true\u00a0# rm -rf /\n"))
        for (c in listOf(0xa0, 0x1680, 0x2000, 0x200a, 0x202f, 0x205f, 0x3000, 0x2028, 0x2029, 0x85, 0x0b, 0x0c, 0x7f, 0x180e, 0xe000, 0x10ffff, 0x0378,
            0x3164, 0xffa0, 0x115f, 0x1160, 0x2800, 0x034f, 0xfe0f, 0xe0100, 0x17b4, 0x180b, 0x180f)) {
            assertTrue("U+%04X".format(c), RootMessage.hiddenChar(c))
        }
        for (c in listOf(' ', '\t', '\n', 'a', '#', 'é', 'ש', '—', '“', '⟨').map { it.code }) {
            assertFalse("U+%04X".format(c), RootMessage.hiddenChar(c))
        }
        // The helper refuses them in a script, so the phone won't sign one.
        assertNotNull(RootMessage.problem(vector().copy(script = "true\u00a0# x\n")))
        val r = vector().copy(script = s)
        assertTrue(RootMessage.build(r, false).contains("script ${RootMessage.sha256Hex(s.toByteArray(Charsets.UTF_8))}\n"))
    }

    @Test
    fun wordsForTheTimeLimitAndDibssCheck() {
        assertEquals("10 min", RootMessage.timeoutWords(600))
        assertEquals("45 s", RootMessage.timeoutWords(45))
        assertEquals("1 h", RootMessage.timeoutWords(3600))
        val r = vector()
        assertEquals("dibs hasn't checked this one", RootMessage.checkWords(r))
        assertEquals("dibs would approve it: Fine.", RootMessage.checkWords(r.copy(note = "Fine.", pick = "accept")))
        assertEquals("dibs would deny it: Odd.", RootMessage.checkWords(r.copy(note = "Odd.", pick = "reject")))
    }
}
