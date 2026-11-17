package com.coloros16.liquidglass.hook

/** Use the launcher's own semantic ownership. Card type IDs vary by engine/version. */
internal object IconGlassSuggestionContract {
    fun matches(card:Any):Boolean {
        val semantic=runCatching {card.javaClass.getMethod("isSuggestCardNoBg").invoke(card) as? Boolean}.getOrNull()
        if(semantic!=null)return semantic
        if(runCatching {card.javaClass.getMethod("getMSuggestNoPaddingViewWrapper").invoke(card)}.getOrNull()!=null)return true
        return runCatching {
            val info=card.javaClass.getMethod("getItemInfo").invoke(card) ?: return@runCatching false
            fun value(name:String):Int? {
                var type:Class<*>?=info.javaClass
                while(type!=null) {
                    val current=type
                    val field=runCatching {current.getDeclaredField(name).apply {isAccessible=true}}.getOrNull()
                    if(field!=null)return (field.get(info) as? Number)?.toInt()
                    type=current.superclass
                }
                return null
            }
            value("itemType")==100 && value("mCardType") in setOf(82,83)
        }.getOrDefault(false)
    }
}
