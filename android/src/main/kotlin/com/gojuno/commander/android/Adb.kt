package com.gojuno.commander.android

import com.gojuno.commander.os.Notification
import com.gojuno.commander.os.log
import com.gojuno.commander.os.nanosToHumanReadableTime
import com.gojuno.commander.os.process
import rx.Observable
import rx.Single
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.TimeUnit.SECONDS

val androidHome: String by lazy { requireNotNull(System.getenv("ANDROID_HOME")) { "Please specify ANDROID_HOME env variable" } }
val adb: String by lazy { "$androidHome${File.pathSeparator}platform-tools${File.pathSeparator}adb" }
private val buildTools: String? by lazy {
    File(androidHome, "build-tools")
            .listFiles()
            .sortedArray()
            .lastOrNull()
            ?.absolutePath
}
val aapt: String by lazy { buildTools?.let { "$buildTools${File.pathSeparator}aapt" } ?: "" }

internal fun Observable<Notification>.trimmedOutput() = this
        .ofType(Notification.Exit::class.java)
        .toSingle()
        .map { it.output.readText().trim() }

fun AdbDevice.externalStorage(): Single<String> = process(listOf(adb, "-s", id, "shell", "printenv", "EXTERNAL_STORAGE"))
        .trimmedOutput()
        .doOnError { log("Could not get external storage of device $id, error = $it") }

fun AdbDevice.deviceModel(): Single<String> = process(listOf(adb, "-s", id, "shell", "getprop ro.product.model undefined"))
        .trimmedOutput()
        .doOnError { log("Could not get model name of device $id, error = $it") }

fun connectedAdbDevices(): Observable<Set<AdbDevice>> = process(listOf(adb, "devices"), unbufferedOutput = true)
        .ofType(Notification.Exit::class.java)
        .map { it.output.readText() }
        .map {
            when (it.contains("List of devices attached")) {
                true -> it
                false -> throw IllegalStateException("Adb output is not correct: $it.")
            }
        }
        .retry { retryCount, exception ->
            val shouldRetry = retryCount < 5 && exception is IllegalStateException
            if (shouldRetry) {
                log("connectedAdbDevices: retrying $exception.")
            }

            shouldRetry
        }
        .flatMapIterable {
            it
                    .substringAfter("List of devices attached")
                    .split(System.lineSeparator())
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .filter { it.contains("online") || it.contains("device") }
        }
        .map { line ->
            val serial = line.substringBefore("\t")
            val online = when {
                line.contains("offline", ignoreCase = true) -> false
                line.contains("device", ignoreCase = true) -> true
                else -> throw IllegalStateException("Unknown adb output for device: $line")
            }
            AdbDevice(id = serial, online = online)
        }
        .flatMapSingle { device ->
            device.deviceModel().map { model ->
                device.copy(model = model)
            }
        }
        .toList()
        .map { it.toSet() }
        .doOnError { log("Error during getting connectedAdbDevices, error = $it") }

fun AdbDevice.log(message: String) = com.gojuno.commander.os.log("[$id] $message")

fun AdbDevice.installApk(pathToApk: String, timeout: Pair<Int, TimeUnit> = 2 to MINUTES): Observable<Unit> {
    val adbDevice = this
    val installApk = process(
            commandAndArgs = listOf(adb, "-s", adbDevice.id, "install", "-t", "-r", pathToApk),
            unbufferedOutput = true,
            timeout = timeout
    )

    return Observable
            .fromCallable { System.nanoTime() }
            .flatMap { startTimeNanos -> installApk.ofType(Notification.Exit::class.java).map { it to startTimeNanos } }
            .map { (exit, startTimeNanos) ->
                val success = exit
                        .output
                        .readText()
                        .split(System.lineSeparator())
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .firstOrNull { it.equals("Success", ignoreCase = true) } != null

                val duration = System.nanoTime() - startTimeNanos

                when (success) {
                    true -> {
                        adbDevice.log("Successfully installed apk in ${duration.nanosToHumanReadableTime()}, pathToApk = $pathToApk")
                    }

                    false -> {
                        adbDevice.log("Failed to install apk $pathToApk")
                        System.exit(1)
                    }
                }
            }
            .doOnSubscribe { adbDevice.log("Installing apk... pathToApk = $pathToApk") }
            .doOnError { adbDevice.log("Error during installing apk: $it, pathToApk = $pathToApk") }
}

fun AdbDevice.pullFolder(folderOnDevice: String, folderOnHostMachine: File, logErrors: Boolean, timeout: Pair<Int, TimeUnit> = 60 to SECONDS): Single<Boolean> {
    val adbDevice = this
    val pullFiles = process(
            commandAndArgs = listOf(adb, "-s", adbDevice.id, "pull", folderOnDevice, folderOnHostMachine.absolutePath),
            timeout = timeout,
            unbufferedOutput = true
    )

    return pullFiles
            .ofType(Notification.Exit::class.java)
            .retry(3)
            .doOnError { error -> if (logErrors) log("Failed to pull files from $folderOnDevice to $folderOnHostMachine failed: $error") }
            .map { true }
            .onErrorReturn { false }
            .toSingle()
}

fun AdbDevice.redirectLogcatToFile(file: File): Single<Process> = Observable
        .fromCallable { file.parentFile.mkdirs() }
        .flatMap { process(listOf(adb, "-s", this.id, "logcat"), redirectOutputTo = file, timeout = null) }
        .ofType(Notification.Start::class.java)
        .doOnError {
            when (it) {
                is InterruptedException -> Unit // Expected case, interrupt comes from System.exit(0).
                else -> this.log("Error during redirecting logcat to file $file, error = $it")
            }
        }
        .map { it.process }
        .take(1)
        .toSingle()
