package io.github.cptmaverick_22.zardit.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun `trusted url guard only allows github release HTTPS origins`() {
        assertTrue(AppUpdater.isTrustedAppUrl("https://api.github.com/repos/Cptmaverick-22/zardit/releases/latest"))
        assertTrue(AppUpdater.isTrustedAppUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertFalse(AppUpdater.isTrustedAppUrl("http://github.com/Cptmaverick-22/zardit"))
        assertFalse(AppUpdater.isTrustedAppUrl("https://evil.example.com/app.apk"))
        assertFalse(AppUpdater.isTrustedAppUrl("https://user:pass@github.com/Cptmaverick-22/zardit"))
        assertFalse(AppUpdater.isTrustedAppUrl("https://127.0.0.1/app.apk"))
    }

    @Test
    fun `parseSha256Sums and parseIsoEpochSec parse standard release assets`() {
        val hexA = "a".repeat(64)
        val hexB = "b".repeat(64)
        val sums = AppUpdater.parseSha256Sums(
            """
            # release checksums
            $hexA  Zardit-1.4.1-android-arm64.apk
            $hexB *Zardit-1.4.1-win64-setup.exe
            """.trimIndent(),
        )
        assertEquals(hexA, sums["Zardit-1.4.1-android-arm64.apk"])
        assertEquals(hexB, sums["Zardit-1.4.1-win64-setup.exe"])
        assertEquals(1700000000L, AppUpdater.parseIsoEpochSec("2023-11-14T22:13:20Z"))
    }

    @Test
    fun `shouldFlagUpdate catches newer semver AND same-tag hotfixes`() {
        val newVer = AppUpdater.shouldFlagUpdate(
            localVersion = "1.4.1",
            remoteTag = "v1.5",
            localCommit = "aaaa",
            remoteCommit = "aaaa",
            localBuiltAtSec = 1000L,
            assetUpdatedAtSec = 1000L,
            assetSha256 = "c".repeat(64),
            installedSha256 = "",
        )
        assertTrue(newVer.available)
        assertFalse(newVer.sameTagHotfix)

        // Same version tag, different commit -> same-tag hotfix
        val hotfixByCommit = AppUpdater.shouldFlagUpdate(
            localVersion = "1.4.1",
            remoteTag = "v1.4.1",
            localCommit = "1111111111",
            remoteCommit = "2222222222",
            localBuiltAtSec = 1700000000L,
            assetUpdatedAtSec = 1700000100L,
            assetSha256 = "d".repeat(64),
            installedSha256 = "",
        )
        assertTrue(hotfixByCommit.available)
        assertTrue(hotfixByCommit.sameTagHotfix)

        // Same version tag, same/empty commit, asset updated > 20m after build -> hotfix
        val hotfixByTime = AppUpdater.shouldFlagUpdate(
            localVersion = "1.4.1",
            remoteTag = "v1.4.1",
            localCommit = "",
            remoteCommit = "",
            localBuiltAtSec = 1700000000L,
            assetUpdatedAtSec = 1700002000L,
            assetSha256 = "d".repeat(64),
            installedSha256 = "",
        )
        assertTrue(hotfixByTime.available)
        assertTrue(hotfixByTime.sameTagHotfix)

        // Once installed SHA matches remote asset SHA -> no repeat prompt
        val alreadyInstalled = AppUpdater.shouldFlagUpdate(
            localVersion = "1.4.1",
            remoteTag = "v1.4.1",
            localCommit = "1111111111",
            remoteCommit = "2222222222",
            localBuiltAtSec = 1700000000L,
            assetUpdatedAtSec = 1700002000L,
            assetSha256 = "d".repeat(64),
            installedSha256 = "d".repeat(64),
        )
        assertFalse(alreadyInstalled.available)
    }
}
