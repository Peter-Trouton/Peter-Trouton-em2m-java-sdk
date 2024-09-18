package io.em2m.search.core.model

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import org.locationtech.jts.geom.Envelope

@JsonPropertyOrder("op")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes(
    Type(value = AndQuery::class, name = "and"),
    Type(value = OrQuery::class, name = "or"),
    Type(value = NotQuery::class, name = "not"),
    Type(value = RangeQuery::class, name = "range"),
    Type(value = DateRangeQuery::class, name = "date_range"),
    Type(value = TermQuery::class, name = "term"),
    Type(value = TermsQuery::class, name = "terms"),
    Type(value = MatchQuery::class, name = "match"),
    Type(value = RegexQuery::class, name = "regex"),
    Type(value = PrefixQuery::class, name = "prefix"),
    Type(value = PhraseQuery::class, name = "phrase"),
    Type(value = ExistsQuery::class, name = "exists"),
    Type(value = BboxQuery::class, name = "bbox"),
    Type(value = MatchAllQuery::class, name = "all"),
    Type(value = WildcardQuery::class, name = "wildcard"),
    Type(value = LuceneQuery::class, name = "lucene"),
    Type(value = NativeQuery::class, name = "native"),
    Type(value = NamedQuery::class, name = "named")
)
abstract class Query(val label: String?) {
    open fun simplify() = this

    open fun negate(): Query = NotQuery(this)
}

abstract class FieldedQuery(val field: String, label: String?) : Query(label)

abstract class BoolQuery(@JsonProperty("of") val of: List<Query> = emptyList(), label: String?) : Query(label)

class MatchAllQuery(label: String? = null) : Query(label)

class AndQuery(
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    of: List<Query>,
    label: String? = null
) : BoolQuery(of, label) {
    constructor(vararg of: Query) : this(of.asList())

    override fun simplify(): Query {
        val ofx = of
            .map { it.simplify() }
            // lift terms of nested AND into this AND
            .flatMap { if (it is AndQuery) it.of else listOf(it) }
            .filter { it !is MatchAllQuery }

        return when {
            ofx.isEmpty() -> MatchAllQuery()
            ofx.size == 1 -> ofx.first()
            else -> {
                AndQuery(ofx)
            }
        }
    }

    override fun negate(): OrQuery {
        return OrQuery(of.map { it.negate() })
    }

    companion object {
        fun of(vararg query: Query?): Query {
            return AndQuery(query.filterNotNull()).simplify()
        }
    }
}

class OrQuery(
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    of: List<Query>,
    label: String? = null
) : BoolQuery(of, label) {
    constructor(vararg of: Query, label: String? = null) : this(of.asList(), label)

    override fun simplify(): Query {
        val ofx = of
            .map { it.simplify() }
            .flatMap { if (it is OrQuery) it.of else listOf(it) }

        val matchAllCount = ofx.count { it is MatchAllQuery }

        return when {
            matchAllCount > 0 -> MatchAllQuery()
            ofx.isEmpty() -> MatchAllQuery()
            ofx.size == 1 -> ofx.first()
            else -> OrQuery(ofx)
        }
    }

    override fun negate(): AndQuery {
        return AndQuery(of.map { it.negate() })
    }

    companion object {
        fun of(vararg query: Query?, label: String? = null): Query {
            return OrQuery(query.filterNotNull(), label).simplify()
        }
    }
}

class NotQuery(
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    of: List<Query>,
    label: String? = null
) : BoolQuery(of, label) {
    constructor(vararg of: Query) : this(of.asList())

    override fun simplify(): Query {
        val ofx = of.map { it.simplify() }
        return NotQuery(ofx)
    }

    override fun negate(): Query {
        return AndQuery(of)
    }

    companion object {
        fun of(vararg query: Query?): Query {
            return NotQuery(query.filterNotNull()).simplify()
        }
    }
}

class NativeQuery(var value: Any? = null, label: String? = null) : Query(label)
class NamedQuery(var name: String, var value: Any? = null, label: String? = null) : Query(label)

class TermQuery(field: String, val value: Any?, label: String? = null) : FieldedQuery(field, label)
class TermsQuery(
    field: String,
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val value: List<Any?>,
    label: String? = null
) : FieldedQuery(field, label) {

    override fun simplify(): Query {
        return if (value.size < 2) TermQuery(field, value.firstOrNull(), label) else this
    }

}

class MatchQuery(field: String, val value: String, val operator: String? = null, label: String? = null) :
    FieldedQuery(field, label)

class WildcardQuery(field: String, val value: String, label: String? = null) : FieldedQuery(field, label) {

    fun toPattern(): String {
        val result = StringBuilder()
        value.forEach { ch ->
            when (ch) {
                '*' -> result.append(".*")
                '?' -> result.append('.')
                '.' -> result.append("\\.")
                '\\' -> result.append("\\\\")
                '[', ']', '(', ')' -> result.append("\\").append(ch)
                else -> result.append(ch)
            }
        }
        return result.toString()
    }
}

class PhraseQuery(
    field: String,
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val value: List<String>,
    label: String? = null
) : FieldedQuery(field, label)

class PrefixQuery(field: String, val value: String, label: String? = null) : FieldedQuery(field, label)

class RegexQuery(field: String, val value: String, label: String? = null) : FieldedQuery(field, label)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
class RangeQuery(
    field: String,
    val lt: Any? = null,
    val lte: Any? = null,
    val gt: Any? = null,
    val gte: Any? = null,
    val timeZone: String? = null,
    label: String? = null
) : FieldedQuery(field, label)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
class DateRangeQuery(
    field: String,
    val lt: Any? = null,
    val lte: Any? = null,
    val gt: Any? = null,
    val gte: Any? = null,
    val timeZone: String? = null,
    label: String? = null
) : FieldedQuery(field, label)

class BboxQuery(field: String, val value: Envelope, label: String? = null) : FieldedQuery(field, label)

class LuceneQuery(val query: String, val defaultField: String? = null, label: String? = null) : Query(label)

class ExistsQuery(field: String, val value: Boolean = true, label: String? = null) : FieldedQuery(field, label)

