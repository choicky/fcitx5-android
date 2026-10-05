/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.ApkSigningConfig
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.the
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun Project.runCmd(cmd: String, defaultValue: String = ""): String {
    val output = providers.exec {
        commandLine = cmd.split(" ")
    }
    return if (output.result.get().exitValue == 0) {
        output.standardOutput.asText.get().trim()
    } else {
        defaultValue
    }
}

val Project.libs get() = the<LibrariesForLibs>()

val Project.assetsDir: File
    get() = file("src/main/assets").also { it.mkdirs() }

val Project.cleanTask: Task
    get() = tasks.getByName("clean")

val Project.cmakeVersion
    get() = ep("CMAKE_VERSION", "cmakeVersion") { Versions.defaultCMake }

val Project.ndkVersion
    get() = ep("NDK_VERSION", "ndkVersion") { Versions.defaultNDK }

val Project.buildToolsVersion
    get() = ep("BUILD_TOOLS_VERSION", "buildTools") { Versions.defaultBuildTools }

val Project.buildVersionName
    get() = ep("BUILD_VERSION_NAME", "buildVersionName") {
        runCmd("git describe --tags --long --always", Versions.baseVersionName)
    }

val Project.buildCommitHash
    get() = ep("BUILD_COMMIT_HASH", "buildCommitHash") {
        runCmd("git rev-parse HEAD", "N/A")
    }

val Project.buildTimestamp
    get() = ep("BUILD_TIMESTAMP", "buildTimestamp") {
        System.currentTimeMillis().toString()
    }

val Project.buildAbiOverride: String?
    get() = epn("BUILD_ABI", "buildABI")

val Project.signKeyBase64: String?
    get() = epn("SIGN_KEY_BASE64", "signKeyBase64")

val Project.signKeyFile: String?
    get() = epn("SIGN_KEY_FILE", "signKeyFile")

private val signingKeyTempFiles = mutableMapOf<String, File>()

@OptIn(ExperimentalEncodingApi::class)
private fun Project.signingKey(fileValue: String?, base64Value: String?, tempPrefix: String): File? {
    fileValue?.let {
        val file = File(it)
        if (file.exists()) return file
    }
    base64Value?.let { value ->
        val cached = signingKeyTempFiles[tempPrefix]
        if (cached?.exists() == true) return cached
        val buildDir = layout.buildDirectory.asFile.get()
        buildDir.mkdirs()
        val file = File.createTempFile(tempPrefix, ".ks", buildDir)
        return try {
            file.writeBytes(Base64.decode(value))
            file.deleteOnExit()
            signingKeyTempFiles[tempPrefix] = file
            file
        } catch (e: Exception) {
            println(e.localizedMessage ?: e.stackTraceToString())
            file.delete()
            null
        }
    }
    return null
}

val Project.signKey: File?
    get() = signingKey(signKeyFile, signKeyBase64, "sign-")

val Project.signKeyPwd: String?
    get() = epn("SIGN_KEY_PWD", "signKeyPwd")

val Project.signKeyAlias: String?
    get() = epn("SIGN_KEY_ALIAS", "signKeyAlias")

val Project.debugSignKeyBase64: String?
    get() = epn("DEBUG_SIGN_KEY_BASE64", "debugSignKeyBase64")

val Project.debugSignKeyFile: String?
    get() = epn("DEBUG_SIGN_KEY_FILE", "debugSignKeyFile")

val Project.debugSignKeyPwd: String?
    get() = epn("DEBUG_SIGN_KEY_PWD", "debugSignKeyPwd")

// Preferred locally: a path to a file containing the password, so the secret never
// has to appear in a command line, shell history or checked-in properties.
val Project.debugSignKeyPwdFile: String?
    get() = epn("DEBUG_SIGN_KEY_PWD_FILE", "debugSignKeyPwdFile")

val Project.debugSignKeyAlias: String?
    get() = epn("DEBUG_SIGN_KEY_ALIAS", "debugSignKeyAlias")

private fun Project.debugSignKey(): File? =
    signingKey(debugSignKeyFile, debugSignKeyBase64, "debug-sign-")

private fun Project.debugSignKeyPassword(): String? {
    debugSignKeyPwdFile?.let {
        val file = File(it)
        if (file.exists()) return file.readText().trim()
    }
    return debugSignKeyPwd
}

fun NamedDomainObjectContainer<out ApkSigningConfig>.fromProjectEnv(
    project: Project,
    name: String = "release"
): ApkSigningConfig? {
    val keyFile: File?
    val password: String?
    val alias: String?
    if (name == "debug") {
        keyFile = project.debugSignKey()
        password = project.debugSignKeyPassword()
        alias = project.debugSignKeyAlias
    } else {
        keyFile = project.signKey
        password = project.signKeyPwd
        alias = project.signKeyAlias
    }
    keyFile ?: return null
    return findByName(name)?.apply {
        storeFile = keyFile
        storePassword = password
        keyAlias = alias
        keyPassword = password
    } ?: create(name) {
        storeFile = keyFile
        storePassword = password
        keyAlias = alias
        keyPassword = password
    }
}
