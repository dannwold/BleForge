    override suspend fun disconnect() {
        val addr = currentAddress ?: "unknown"
        _connectionState.value = ConnectionState.Disconnecting(addr)
        stopHeartbeat()
        try { super.disconnect().suspend() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}
        close()
        _connectionState.value = ConnectionState.Disconnected
    }
