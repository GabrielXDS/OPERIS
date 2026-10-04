package br.com.alertaequipe

import com.google.firebase.functions.FirebaseFunctionsException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

data class UpdateFailure(val code: String, val message: String)

object UpdateDiagnostics {
    fun http(status: Int): UpdateFailure = when (status) {
        401 -> UpdateFailure("HTTP_401", "O servidor do APK exige autenticação.")
        403 -> UpdateFailure("HTTP_403", "O servidor negou acesso ao APK (HTTP 403).")
        404 -> UpdateFailure("HTTP_404", "O APK não foi encontrado no endereço publicado (HTTP 404).")
        in 500..599 -> UpdateFailure("HTTP_5XX", "O servidor do APK está indisponível (HTTP $status).")
        else -> UpdateFailure("HTTP_ERROR", "O servidor do APK respondeu com erro HTTP $status.")
    }
    fun download(reason: Int): UpdateFailure = when (reason) {
        in 400..599 -> http(reason)
        1006 -> UpdateFailure("STORAGE", "Não há espaço suficiente para baixar a atualização.")
        1007 -> UpdateFailure("STORAGE", "O armazenamento do download não está disponível.")
        1004 -> UpdateFailure("DOWNLOAD_INTERRUPTED", "A transferência do APK foi interrompida. Tente novamente.")
        else -> UpdateFailure("DOWNLOAD", "O Android não conseguiu concluir o download. Tente novamente.")
    }
    suspend fun checkApk(url: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        require(ReleaseInfo.validDownload(url))
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            val status = connection.responseCode
            // DownloadManager follows redirects itself. Never log redirect URLs or query strings.
            if (status !in 200..299 && status !in 300..399 && status != 405) {
                throw UpdateRequestException(http(status))
            }
        } finally { connection.disconnect() }
    }
    fun describe(error: Throwable): UpdateFailure {
        if (error is UpdateRequestException) return error.failure
        var cause: Throwable? = error
        repeat(8) {
            when (cause) {
                is UnknownHostException -> return UpdateFailure("DNS", "Não foi possível resolver o endereço do servidor de atualização. Verifique o DNS da rede.")
                is SocketTimeoutException, is TimeoutCancellationException -> return UpdateFailure("TIMEOUT", "O servidor de atualização demorou para responder. Tente novamente.")
            }
            cause = cause?.cause
        }
        if (error is FirebaseFunctionsException) {
            val reason = (error.details as? Map<*, *>)?.get("reason")
            return when {
                reason == "RELEASE_NOT_FOUND" -> UpdateFailure("RELEASE_NOT_FOUND", "Nenhuma versão foi cadastrada no servidor de atualização.")
                reason == "RELEASE_INVALID" -> UpdateFailure("RELEASE_INVALID", "A configuração da versão no servidor está incompleta.")
                error.code == FirebaseFunctionsException.Code.UNAUTHENTICATED -> UpdateFailure("AUTH_OR_APP_CHECK", "O servidor recusou a autenticação ou a verificação do aplicativo. Tente novamente após entrar no aplicativo.")
                error.code == FirebaseFunctionsException.Code.PERMISSION_DENIED -> UpdateFailure("PERMISSION_DENIED", "O servidor negou acesso à atualização.")
                error.code == FirebaseFunctionsException.Code.NOT_FOUND -> UpdateFailure("ENDPOINT_NOT_FOUND", "O serviço de atualização não foi encontrado no servidor.")
                error.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> UpdateFailure("TIMEOUT", "O servidor de atualização demorou para responder. Tente novamente.")
                error.code == FirebaseFunctionsException.Code.UNAVAILABLE -> UpdateFailure("UNAVAILABLE", "Não foi possível alcançar o serviço de atualização. Verifique a Internet e tente novamente.")
                else -> UpdateFailure("SERVER", "O servidor não conseguiu consultar a atualização. Tente novamente.")
            }
        }
        return UpdateFailure("UNKNOWN", "Não foi possível verificar a atualização. Tente novamente.")
    }
}

class UpdateRequestException(val failure: UpdateFailure) : Exception(failure.code)
