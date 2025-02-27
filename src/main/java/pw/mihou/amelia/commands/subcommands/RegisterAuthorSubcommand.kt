package pw.mihou.amelia.commands.subcommands

import org.javacord.api.entity.message.component.Button
import org.javacord.api.entity.message.embed.EmbedBuilder
import org.javacord.api.event.interaction.ButtonClickEvent
import org.javacord.api.interaction.SlashCommandInteractionOption
import org.javacord.api.interaction.callback.InteractionOriginalResponseUpdater
import org.javacord.api.util.logging.ExceptionLogger
import pw.mihou.Amaririsu
import pw.mihou.amelia.db.FeedDatabase
import pw.mihou.amelia.io.rome.RssReader
import pw.mihou.amelia.models.FeedModel
import pw.mihou.amelia.templates.TemplateMessages
import pw.mihou.amelia.utility.future
import pw.mihou.models.user.UserResultOrAuthor
import pw.mihou.nexus.features.command.facade.NexusCommandEvent
import pw.mihou.nexus.features.paginator.NexusPaginatorBuilder
import pw.mihou.nexus.features.paginator.enums.NexusPaginatorButtonAssignment
import pw.mihou.nexus.features.paginator.facade.NexusPaginatorCursor
import pw.mihou.nexus.features.paginator.facade.NexusPaginatorEvents
import java.awt.Color

object RegisterAuthorSubcommand {

    fun run(event: NexusCommandEvent, subcommand: SlashCommandInteractionOption) {
        val name = subcommand.getArgumentStringValueByName("name").orElseThrow()
        val channel = subcommand.getArgumentChannelValueByName("channel").flatMap { it.asServerTextChannel() }.orElseThrow()

        event.respondLater().thenAccept { updater ->
            if (subcommand.name == "user") {
                future { Amaririsu.search(name) { series.enabled = false } }.thenAccept connector@{ results ->
                    if (results.users.isEmpty()) {
                        updater.setContent("❌ Amelia cannot found any users that matches the query, how about trying something else?").update()
                        return@connector
                    }

                    buttons(NexusPaginatorBuilder(results.users.toList())).setEventHandler(object : NexusPaginatorEvents<UserResultOrAuthor> {
                        override fun onInit(
                            updater: InteractionOriginalResponseUpdater,
                            cursor: NexusPaginatorCursor<UserResultOrAuthor>
                        ) = updater.addEmbed(user(cursor))

                        override fun onPageChange(
                            cursor: NexusPaginatorCursor<UserResultOrAuthor>,
                            event: ButtonClickEvent
                        ) {
                            event.buttonInteraction.message.edit(user(cursor))
                        }

                        override fun onCancel(cursor: NexusPaginatorCursor<UserResultOrAuthor>?, event: ButtonClickEvent) {
                            event.buttonInteraction.message.delete()
                        }

                        override fun onSelect(
                            cursor: NexusPaginatorCursor<UserResultOrAuthor>,
                            buttonEvent: ButtonClickEvent
                        ) {
                            buttonEvent.buttonInteraction.message.createUpdater()
                                .removeAllComponents()
                                .removeAllEmbeds()
                                .setContent(TemplateMessages.NEUTRAL_LOADING)
                                .applyChanges()
                                .thenAccept update@{ message ->
                                    val id = cursor.item.id
                                    val feed = "https://www.rssscribblehub.com/rssfeed.php?type=author&uid=$id"

                                    val latestPosts = RssReader.cached(feed)

                                    if (latestPosts == null) {
                                        message.edit("❌ Amelia encountered a problem while trying to send: ScribbleHub is not accessible.")
                                        return@update
                                    }

                                    if (latestPosts.isEmpty()) {
                                        message.edit(TemplateMessages.ERROR_RSSSCRIBBLEHUB_NOT_ACCESSIBLE)
                                        return@update
                                    }

                                    val latestPost = latestPosts[0]

                                    if (latestPost.date == null) {
                                        message.edit(TemplateMessages.ERROR_DATE_NOT_FOUND)
                                        return@update
                                    }

                                    val result = FeedDatabase.upsert(
                                        FeedModel(
                                            id = id,
                                            unique = FeedDatabase.unique(),
                                            channel = channel.id,
                                            user = event.user.id,
                                            date = latestPost.date,
                                            name = "${cursor.item.name}'s stories",
                                            feedUrl = feed,
                                            mentions = emptyList(),
                                            server = event.server.orElseThrow().id
                                        )
                                    )

                                    if (result.wasAcknowledged()) {
                                        message.edit("✅ I will try my best to send updates for ${cursor.item.name}'s stories in ${channel.mentionTag}!")
                                        return@update
                                    }

                                    message.edit(TemplateMessages.ERROR_DATABASE_FAILED)
                                }.exceptionally {
                                    it.printStackTrace()

                                    buttonEvent.buttonInteraction.message
                                        .edit(TemplateMessages.ERROR_FAILED_TO_PERFORM_ACTION)

                                    return@exceptionally null
                                }
                            cursor.parent().parent.destroy()
                        }
                    }).build().send(event.baseEvent.interaction, updater)
                }.exceptionally {
                    it.printStackTrace()

                    event.respondNow().setContent("❌ Failed to connect to ScribbleHub. It's possible that the site is down or having issues.").respond()
                    return@exceptionally null
                }
                return@thenAccept
            }
        }.exceptionally(ExceptionLogger.get())
    }

    private fun <Type> buttons(paginator: NexusPaginatorBuilder<Type>): NexusPaginatorBuilder<Type> = paginator
        .setButton(NexusPaginatorButtonAssignment.SELECT, Button.primary("", "Select"))
        .setButton(NexusPaginatorButtonAssignment.PREVIOUS, Button.secondary("", "Previous"))
        .setButton(NexusPaginatorButtonAssignment.NEXT, Button.secondary("", "Next"))
        .setButton(NexusPaginatorButtonAssignment.CANCEL, Button.secondary("", "Cancel"))

    private fun user(cursor: NexusPaginatorCursor<UserResultOrAuthor>) =
        EmbedBuilder().setTimestampToNow().setColor(Color.YELLOW).setTitle(cursor.item.name)
            .setDescription(
                "You can create a notification listener for this user by pressing the **Select** button below, "
                        + "please make sure that this is the correct user. "
                        + "If you need to look at their full profile page to be sure, "
                        + "you may visit the link ${cursor.item.url}."
            )
            .setFooter("You are looking at ${cursor.displayablePosition} out of ${cursor.maximumPages} pages")
            .setImage(cursor.item.avatar)

}