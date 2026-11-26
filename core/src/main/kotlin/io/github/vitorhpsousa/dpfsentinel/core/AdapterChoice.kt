package io.github.vitorhpsousa.dpfsentinel.core

// Which OBD adapter the logger may talk to on one connection attempt. Once an adapter is saved, only
// that one (or the one linked for automatic logging) is ever used: a scan that takes "the strongest
// adapter-like name" runs only when nothing is saved, so a neighbour's or another car's adapter can
// never replace the saved one unnoticed. A new adapter is taken after Settings -> Forget adapter.
object AdapterChoice {
    /**
     * [connectTo]: connect straight to this address without scanning, or "" to scan.
     * [scanFor]: the only address a scan may return, or "" for any adapter-like name.
     */
    data class Plan(val connectTo: String, val scanFor: String)

    /**
     * [saved]: the adapter used last time, [linked]: the one paired for automatic logging (either
     * may be ""), [fails]: failed attempts in a row. Every third attempt scans for the adapter
     * instead of connecting by address, which finds it when Android's cached address has gone stale.
     */
    fun plan(saved: String, linked: String, fails: Int): Plan = when {
        saved.isEmpty() -> Plan("", linked)
        fails % 3 != 2 -> Plan(saved, "")
        else -> Plan("", linked.ifEmpty { saved })
    }
}
