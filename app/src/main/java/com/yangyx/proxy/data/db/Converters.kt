package com.yangyx.proxy.data.db

import androidx.room.TypeConverter
import com.yangyx.proxy.data.model.ProxyType
import com.yangyx.proxy.data.model.RuleAction

class Converters {
    @TypeConverter
    fun fromProxyType(value: ProxyType): String = value.name

    @TypeConverter
    fun toProxyType(value: String): ProxyType = runCatching { ProxyType.valueOf(value) }.getOrDefault(ProxyType.SOCKS5)

    @TypeConverter
    fun fromRuleAction(value: RuleAction): String = value.name

    @TypeConverter
    fun toRuleAction(value: String): RuleAction = runCatching { RuleAction.valueOf(value) }.getOrDefault(RuleAction.PROXY)

    @TypeConverter
    fun fromStringList(list: List<String>): String = list.joinToString(";;;")

    @TypeConverter
    fun toStringList(data: String): List<String> = if (data.isEmpty()) emptyList() else data.split(";;;")
}
