package li.songe.zadixsource.data

sealed class ResolvedGroup(
    open val group: RawSubscription.RawGroupProps,
    val subscription: RawSubscription,
    val subsItem: SubsItem,
    val config: SubsConfig?,
    val session: RuleSession,
) {
    val excludeData = ExcludeData.parse(config?.exclude)
}

class ResolvedAppGroup(
    override val group: RawSubscription.RawAppGroup,
    subscription: RawSubscription,
    subsItem: SubsItem,
    config: SubsConfig?,
    session: RuleSession,
    val app: RawSubscription.RawApp,
    val enable: Boolean,
) : ResolvedGroup(group, subscription, subsItem, config, session)

class ResolvedGlobalGroup(
    override val group: RawSubscription.RawGlobalGroup,
    subscription: RawSubscription,
    subsItem: SubsItem,
    config: SubsConfig?,
    session: RuleSession,
) : ResolvedGroup(group, subscription, subsItem, config, session)