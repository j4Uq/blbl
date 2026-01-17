package blbl.cat3399.feature.live

interface LivePageFocusTarget {
    fun requestFocusFirstCardFromTab(): Boolean

    fun requestFocusFirstCardFromContentSwitch(): Boolean
}

