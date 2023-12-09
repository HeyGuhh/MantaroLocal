/*
 * Copyright (C) 2016 Kodehawa
 *
 * Mantaro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Mantaro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 *
 */

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.utils.SplitUtil;
import net.kodehawa.mantarobot.commands.utils.reminders.Reminder;
import net.kodehawa.mantarobot.commands.utils.reminders.ReminderObject;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.command.TextCommand;
import net.kodehawa.mantarobot.core.command.TextContext;
import net.kodehawa.mantarobot.core.command.meta.Category;
import net.kodehawa.mantarobot.core.command.meta.Defer;
import net.kodehawa.mantarobot.core.command.meta.Description;
import net.kodehawa.mantarobot.core.command.meta.Ephemeral;
import net.kodehawa.mantarobot.core.command.meta.Help;
import net.kodehawa.mantarobot.core.command.meta.Name;
import net.kodehawa.mantarobot.core.command.meta.Options;
import net.kodehawa.mantarobot.core.command.slash.SlashCommand;
import net.kodehawa.mantarobot.core.command.slash.SlashContext;
import net.kodehawa.mantarobot.core.command.meta.Module;
import net.kodehawa.mantarobot.core.command.helpers.CommandCategory;
import net.kodehawa.mantarobot.core.command.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.DiscordUtils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import org.json.JSONObject;
import redis.clients.jedis.Jedis;

import java.awt.Color;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Module
public class UtilsCmds {
    private static final Pattern rawTimePattern = Pattern.compile("^[(\\d)((?d|?h|(?m|(?s)]+$");

    @Subscribe
    public void register(CommandRegistry cr) {
        cr.registerSlash(Time.class);
        cr.registerSlash(RemindMe.class);
        cr.register(Wiki.class);
    }

    @Description("Reminds you of something.")
    @Category(CommandCategory.UTILS)
    @Help(description = "The hub for reminder related commands. Check subcommand help for more help.")
    public static class RemindMe extends SlashCommand {
        @Override
        protected void process(SlashContext ctx) {}

        @Description("Adds a reminder.")
        @Defer
        @Ephemeral
        @Options({
                @Options.Option(type = OptionType.STRING, name = "time", description = "How much time until I remind you of it. Time is in this format: 1h20m (1 hour and 20m)", required = true),
                @Options.Option(type = OptionType.STRING, name = "reminder", description = "The thing to remind you of.", required = true)
        })
        @Help(description = "Reminds you of something.", usage = "`/remindme add time:<time until> reminder:<reminder text>`", parameters = {
                @Help.Parameter(name = "time", description = "How much time until I remind you of it. Time is in this format: 1h20m (1 hour and 20m)"),
                @Help.Parameter(name = "reminder", description = "The thing to remind you of.")
        })
        public static class Add extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                long time = 0;
                final var maybeTime = ctx.getOptionAsString("time");
                final var matchTime = rawTimePattern.matcher(maybeTime).matches();
                if (matchTime) {
                    time = Utils.parseTime(maybeTime);
                }

                if (time == 0) {
                    ctx.reply("commands.remindme.no_time", EmoteReference.ERROR);
                    return;
                }

                var toRemind = ctx.getOptionAsString("reminder");
                var user = ctx.getAuthor();
                var dbUser = ctx.getDBUser();
                var rems = getReminders(dbUser.getReminders());

                if (rems.size() > 25) {
                    //Max amount of reminders reached
                    ctx.reply("commands.remindme.too_many_reminders", EmoteReference.ERROR);
                    return;
                }

                if (time < 60000) {
                    ctx.reply("commands.remindme.too_little_time", EmoteReference.ERROR);
                    return;
                }

                if (System.currentTimeMillis() + time > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365)) {
                    ctx.reply("commands.remindme.too_long", EmoteReference.ERROR);
                    return;
                }

                var displayRemind = toRemind
                        .replaceAll(Utils.DISCORD_INVITE.pattern(), "discord invite link")
                        .replaceAll(Utils.DISCORD_INVITE_2.pattern(), "discord invite link")
                        .replaceAll(Utils.HTTP_URL.pattern(), "url")
                        .trim();

                if (displayRemind.isEmpty()) {
                    displayRemind = "something";
                }

                ctx.reply("commands.remindme.success", EmoteReference.CORRECT, Utils.getTagOrDisplay(ctx.getAuthor()),
                        displayRemind, Utils.formatDuration(ctx.getLanguageContext(), time)
                );

                new Reminder.Builder()
                        .id(user.getId())
                        .guild(ctx.getGuild().getId())
                        .reminder(toRemind)
                        .current(System.currentTimeMillis())
                        .time(time + System.currentTimeMillis())
                        .build()
                        .schedule(); //automatic
            }
        }

        @Description("Cancels a reminder.")
        public static class Cancel extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                try {
                    var reminders = ctx.getDBUser().getReminders();
                    if (reminders.isEmpty()) {
                        ctx.replyEphemeral("commands.remindme.no_reminders", EmoteReference.ERROR);
                        return;
                    }

                    if (reminders.size() == 1) {
                        Reminder.cancel(ctx.getAuthor().getId(), reminders.get(0), Reminder.CancelReason.CANCEL); // Cancel first reminder.
                        ctx.replyEphemeral("commands.remindme.cancel.success", EmoteReference.CORRECT);
                    } else {
                        I18nContext lang = ctx.getLanguageContext();
                        List<ReminderObject> rems = getReminders(reminders);
                        rems = rems.stream().filter(reminder -> reminder.time - System.currentTimeMillis() > 3).collect(Collectors.toList());
                        DiscordUtils.selectListButtonSlash(ctx, rems,
                                r -> "%s, %s: <t:%s:R>".formatted(r.reminder, lang.get("commands.remindme.due_at"), r.time / 1000),
                                r1 -> new EmbedBuilder().setColor(Color.CYAN).setTitle(lang.get("commands.remindme.cancel.select"), null)
                                        .setDescription(r1)
                                        .setFooter(lang.get("general.timeout").formatted(10), null).build(),
                                (sr, hook) -> {
                                    Reminder.cancel(ctx.getAuthor().getId(), sr.id + ":" + sr.getUserId(), Reminder.CancelReason.CANCEL);
                                    hook.editOriginal(EmoteReference.CORRECT + "Cancelled your reminder").setEmbeds().setComponents().queue();
                                });
                    }
                } catch (Exception e) {
                    ctx.replyEphemeral("commands.remindme.no_reminders", EmoteReference.ERROR);
                }
            }
        }

        @Name("list")
        @Defer
        @Ephemeral
        @Description("Lists your reminders")
        public static class ListReminders extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var reminders = ctx.getDBUser().getReminders();
                var rms = getReminders(reminders).stream()
                        .sorted(Comparator.comparingLong(ReminderObject::getScheduledAtMillis)).toList();

                if (rms.isEmpty()) {
                    ctx.sendLocalized("commands.remindme.no_reminders", EmoteReference.ERROR);
                    return;
                }

                var builder = new StringBuilder();
                var i = new AtomicInteger();
                for (var rems : rms) {
                    builder.append(
                            String.format("**%,d.-** %s: *%s*. %s: **<t:%s>**",
                                    i.incrementAndGet(), ctx.getLanguageContext().get("commands.remindme.content"),
                                    rems.getReminder(), ctx.getLanguageContext().get("commands.remindme.due_at"),
                                    rems.getTime() / 1000
                            )
                    ).append("\n");
                }

                var split = SplitUtil.split(builder.toString(), Message.MAX_CONTENT_LENGTH, true, SplitUtil.Strategy.NEWLINE);
                DiscordUtils.listButtons(ctx.getUtilsContext(), 60, split);
            }
        }
    }

    @Name("time")
    @Category(CommandCategory.UTILS)
    @Description("Get the time in a specific timezone.")
    @Help(description = "Get the time in a specific timezone (GMT).",
            usage = "`/time timezone:[zone] user:[@user]`",
            parameters = {
                @Help.Parameter(name = "timezone", description = "The timezone in GMT or UTC offset (Example: GMT-3) or a ZoneId (such as Europe/London)"),
                @Help.Parameter(name = "user", description = "The user to see the timezone of.")
            }
    )
    @Options({
            @Options.Option(type = OptionType.USER, name = "user", description = "The user to check the timezone of."),
            @Options.Option(type = OptionType.STRING, name = "timezone", description = "The timezone to check.")
    })
    public static class Time extends SlashCommand {
        final Pattern offsetRegex = Pattern.compile("(?:UTC|GMT)[+-][0-9]{1,2}(:[0-9]{1,2})?", Pattern.CASE_INSENSITIVE);

        @Override
        protected void process(SlashContext ctx) {
            var user = ctx.getOptionAsUser("user");
            var timezone = ctx.getOptionAsString("timezone", "");
            if (offsetRegex.matcher(timezone).matches()) {
                timezone = timezone.toUpperCase().replace("UTC", "GMT");
            }

            var dbUser = user == null ? ctx.getDBUser() : ctx.getDBUser(user.getId());
            if (user != null && dbUser.getTimezone() == null) {
                ctx.reply("commands.time.user_no_timezone", EmoteReference.ERROR);
                return;
            }

            if (dbUser.getTimezone() != null && (timezone.isEmpty() || user != null)) {
                timezone = dbUser.getTimezone();
            }

            if (!Utils.isValidTimeZone(timezone)) {
                ctx.reply("commands.time.invalid_timezone", EmoteReference.ERROR);
                return;
            }

            var dateFormat = "";
            try {
                dateFormat = Utils.formatDate(LocalDateTime.now(Utils.timezoneToZoneID(timezone)), dbUser.getLang());
            } catch (DateTimeException e) {
                ctx.reply("commands.time.invalid_timezone", EmoteReference.ERROR);
                return;
            }

            ctx.reply("commands.time.success", EmoteReference.CLOCK, dateFormat, timezone);
        }
    }

    private static List<ReminderObject> getReminders(List<String> reminders) {
        try (Jedis j = MantaroData.getDefaultJedisPool().getResource()) {
            List<ReminderObject> rems = new ArrayList<>();
            for (String s : reminders) {
                var rem = j.hget("reminder", s);
                if (rem != null) {
                    var json = new JSONObject(rem);
                    rems.add(ReminderObject.builder()
                            .id(s.split(":")[0])
                            .userId(json.getString("user"))
                            .guildId(json.getString("guild"))
                            .scheduledAtMillis(json.getLong("scheduledAt"))
                            .time(json.getLong("at"))
                            .reminder(json.getString("reminder"))
                            .build());
                }
            }

            return rems;
        }
    }

    @Category(CommandCategory.UTILS)
    @Description("Shows a bunch of things related to Mantaro's wiki.")
    public static class Wiki extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            ctx.send(EmoteReference.OK + "**For Mantaro's documentation please visit:** https://www.mantaro.site/mantaro-wiki");
        }

        public static class Opts extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For Mantaro's documentation on `~>opts` and general bot options please visit:** https://www.mantaro.site/mantaro-wiki/basics/server-configuration");
            }
        }

        public static class Custom extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For Mantaro's documentation on custom commands please visit:** https://www.mantaro.site/mantaro-wiki/guides/custom-commands");
            }
        }

        public static class Modifiers extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For Mantaro's documentation in custom commands modifiers please visit:** https://www.mantaro.site/mantaro-wiki/guides/modifiers");
            }
        }

        public static class Commands extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For Mantaro's documentation on commands and usage please visit:** https://www.mantaro.site/mantaro-wiki/commands/permissions and navigate the pages in the section.");
            }
        }

        public static class Faq extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For Mantaro's FAQ please visit:** https://www.mantaro.site/mantaro-wiki/basics/FAQ");
            }
        }

        public static class Badges extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For Mantaro's badge documentation please visit:** https://www.mantaro.site/mantaro-wiki/currency/badges");
            }
        }

        public static class Tos extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For Mantaro's ToS please visit:** https://www.mantaro.site/mantaro-wiki/legal/terms-of-service");
            }
        }

        public static class UserMessage extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For Mantaro's Welcome and Leave message tutorial please visit:** https://www.mantaro.site/mantaro-wiki/guides/welcome-and-leave-messages");
            }
        }

        public static class Premium extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**To see what Mantaro's Premium features offer please visit:** https://www.mantaro.site/mantaro-wiki/basics/premium-perks");
            }
        }

        public static class Currency extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For a Currency guide, please visit:** https://www.mantaro.site/mantaro-wiki/currency/101");
            }
        }

        public static class Items extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For a list of items, please visit:** https://www.mantaro.site/mantaro-wiki/currency/items");
            }
        }

        public static class Overview extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For a feature overview, check:** https://mantaro.site/features.html");
            }
        }

        public static class Birthday extends TextCommand {
            @Override
            protected void process(TextContext ctx) {
                ctx.send(EmoteReference.OK + "**For a guide on the birthday system, please visit:** https://www.mantaro.site/mantaro-wiki/guides/birthday-announcer");
            }
        }
    }
}
