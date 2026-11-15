package com.letsgo.chageun.core

/** The 14 entries of "내 차 기능 도감". [detectable] features can be confirmed from vehicle signals. */
enum class FeatureId(val title: String, val detectable: Boolean, val core: Boolean = false) {
    SEAT_HEAT("시트 열선", true, core = true),
    FRONT_DEFROST("앞유리 서리 제거", true, core = true),
    DRIVE_ASSIST("주행 보조 버튼", true, core = true),
    WHEEL_HEAT("핸들 열선", true),
    READING_LIGHT("독서등·실내등", true),
    AUTO_LIGHT("라이트 AUTO", true),
    TURN_SIGNAL("방향지시등", true),
    PASSENGER_TEMP("조수석 온도 따로 맞추기", true),
    GLEO_VOICE("글레오 음성 명령", false),
    REMOTE_PARKING("원격 주차", false),
    DIGITAL_KEY("디지털 키", false),
    BUILTIN_CAM("빌트인 캠", false),
    HUD("헤드업 디스플레이", false),
    ONE_TOUCH_SIGNAL("원터치 방향지시등", false),
}

enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }

/** Ordered: a later value always means more evidence, so progress only moves forward. */
enum class Progress { NOT_STARTED, SKIPPED, GUIDANCE_SEEN, VEHICLE_CONFIRMED }

enum class Review { NONE, AIDED, UNAIDED_SUCCESS }

data class FeatureRecord(
    val support: Support = Support.UNKNOWN,
    val progress: Progress = Progress.NOT_STARTED,
    val review: Review = Review.NONE,
    /** Epoch day the core puzzle for this feature was first finished; the review is due from the next day. */
    val learnedDay: Long? = null,
)

data class DexSummary(
    /** Features this car has (or that are explain-only), excluding unsupported and unknown. */
    val available: Int,
    /** "차에서 확인": a vehicle state change was observed during practice. */
    val vehicleConfirmed: Int,
    /** "안내 확인": done on screen, explain-only, or the change could not be observed. */
    val guidanceSeen: Int,
    val unsupported: Int,
    val unknown: Int,
)

interface LearningStore {
    fun load(): Map<FeatureId, FeatureRecord>
    fun save(records: Map<FeatureId, FeatureRecord>)
}

/** Honest learning records on three axes: support, progress, review. */
class LearningLog(private val store: LearningStore) {
    private val records: MutableMap<FeatureId, FeatureRecord> =
        FeatureId.entries.associateWith { FeatureRecord() }.toMutableMap().apply { putAll(store.load()) }

    operator fun get(id: FeatureId): FeatureRecord = records.getValue(id)

    fun setSupport(id: FeatureId, support: Support) = update(id) { it.copy(support = support) }

    fun advance(id: FeatureId, progress: Progress) = update(id) {
        if (progress.ordinal > it.progress.ordinal) it.copy(progress = progress) else it
    }

    fun review(id: FeatureId, review: Review) = update(id) {
        if (review.ordinal > it.review.ordinal) it.copy(review = review) else it
    }

    /** A core puzzle was finished. Only the first day counts, and only when the player at least saw the guidance. */
    fun markLearned(id: FeatureId, day: Long) = update(id) {
        if (it.learnedDay == null && it.progress.ordinal >= Progress.GUIDANCE_SEEN.ordinal) it.copy(learnedDay = day) else it
    }

    /** Core features learned on an earlier day and not reviewed yet, oldest first. */
    fun dueReviews(today: Long): List<FeatureId> = records.entries
        .filter { (id, r) -> id.core && r.review == Review.NONE && r.learnedDay != null && r.learnedDay < today }
        .sortedBy { it.value.learnedDay }
        .map { it.key }

    fun summary(): DexSummary {
        var available = 0; var confirmed = 0; var guidance = 0; var unsupported = 0; var unknown = 0
        for ((id, r) in records) {
            val support = if (id.detectable) r.support else Support.SUPPORTED
            when (support) {
                Support.UNSUPPORTED -> { unsupported++; continue }
                Support.UNKNOWN -> { unknown++; continue }
                Support.SUPPORTED -> available++
            }
            when (r.progress) {
                Progress.VEHICLE_CONFIRMED -> confirmed++
                Progress.GUIDANCE_SEEN -> guidance++
                else -> Unit
            }
        }
        return DexSummary(available, confirmed, guidance, unsupported, unknown)
    }

    private inline fun update(id: FeatureId, change: (FeatureRecord) -> FeatureRecord) {
        val next = change(records.getValue(id))
        if (next != records[id]) {
            records[id] = next
            store.save(records.toMap())
        }
    }
}
