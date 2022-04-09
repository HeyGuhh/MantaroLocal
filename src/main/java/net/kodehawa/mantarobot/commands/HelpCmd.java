/*
 * Copyright (C) 2016-2021 David Rubio Escares / Kodehawa
 *
 *  Mantaro is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  Mantaro is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.Button;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.command.processor.CommandProcessor;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.AliasCommand;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.CommandCategory;
import net.kodehawa.mantarobot.core.modules.commands.base.CommandPermission;
import net.kodehawa.mantarobot.core.modules.commands.base.Context;
import net.kodehawa.mantarobot.core.modules.commands.base.ITreeCommand;
import net.kodehawa.mantarobot.core.modules.commands.help.HelpContent;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.ratelimit.IncreasingRateLimiter;
import net.kodehawa.mantarobot.utils.commands.ratelimit.RatelimitUtils;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.kodehawa.mantarobot.commands.info.HelpUtils.forType;
import static net.kodehawa.mantarobot.utils.commands.EmoteReference.BLUE_SMALL_MARKER;

@Module
public class HelpCmd {
    private void buildHelp(Context ctx, CommandCategory category) {
        var dbGuild = ctx.getDBGuild();
        var guildData = dbGuild.getData();
        var dbUser = ctx.getDBUser();
        var languageContext = ctx.getLanguageContext();

        // Start building the help description.
        var description = new StringBuilder();
        if (category == null) {
            description.append(languageContext.get("commands.help.base"));
        } else {
            description.append(languageContext.get("commands.help.base_category")
                    .formatted(languageContext.get(category.toString()))
            );
        }

        if (!dbUser.isPremium() && !dbGuild.isPremium()) {
            description.append(languageContext.get("commands.help.patreon"));
        }

        var disabledCommands = guildData.getDisabledCommands();
        if (!disabledCommands.isEmpty()) {
            description.append(languageContext.get("commands.help.disabled_commands").formatted(disabledCommands.size()));
        }

        var channelSpecificDisabledCommands = guildData.getChannelSpecificDisabledCommands();
        var disabledChannelCommands = channelSpecificDisabledCommands.get(ctx.getChannel().getId());
        if (disabledChannelCommands != null && !disabledChannelCommands.isEmpty()) {
            description.append("\n");
            description.append(
                    languageContext.get("commands.help.channel_specific_disabled_commands")
                            .formatted(disabledChannelCommands.size())
            );
        }
        // End of help description.

        EmbedBuilder embed = new EmbedBuilder()
                .setAuthor(languageContext.get("commands.help.title"), null, ctx.getGuild().getIconUrl())
                .setColor(Color.PINK)
                .setDescription(description.toString())
                .setFooter(languageContext.get("commands.help.footer").formatted(
                        "❤️", CommandProcessor.REGISTRY.commands()
                                .values()
                                .stream()
                                .filter(c -> c.category() != null)
                                .count()
                ), ctx.getGuild().getIconUrl());

        Arrays.stream(CommandCategory.values())
                .filter(c -> {
                    if (category != null) {
                        return c == category;
                    } else {
                        return true;
                    }
                })
                .filter(c -> c != CommandCategory.HIDDEN)
                .filter(c -> c != CommandCategory.OWNER || CommandPermission.OWNER.test(ctx.getMember()))
                .filter(c -> !CommandProcessor.REGISTRY.getCommandsForCategory(c).isEmpty())
                .forEach(c ->
                        embed.addField(
                                languageContext.get(c.toString()) + " " + languageContext.get("commands.help.commands") + ":",
                                forType(ctx.getChannel(), guildData, c), false
                        )
                );

        ctx.send(embed.build(),
                ActionRow.of(
                        Button.link("https://patreon.com/mantaro", "Patreon"),
                        Button.link("https://wiki.mantaro.site", "More Help"),
                        Button.link("https://support.mantaro.site", "Support Server"),
                        Button.link("https://twitter.com/mantarodiscord", "Twitter")
                )
        );
    }

    @Subscribe
    public void help(CommandRegistry cr) {
        final IncreasingRateLimiter rateLimiter = new IncreasingRateLimiter.Builder()
                .limit(1)
                .spamTolerance(2)
                .cooldown(2, TimeUnit.SECONDS)
                .maxCooldown(3, TimeUnit.SECONDS)
                .randomIncrement(true)
                .pool(MantaroData.getDefaultJedisPool())
                .prefix("help")
                .build();

        Random r = new Random();
        java.util.List<String> jokes = List.of(
                "Yo damn I heard you like help, because you just issued the help command to get the help about the help command.",
                "Congratulations, you managed to use the help command.",
                "Helps you to help yourself.",
                "Help Inception.",
                "A help helping helping helping help.",
                "I wonder if this is what you are looking for...",
                "Helping you help the world.",
                "The help you might need.",
                "Halp!"
        );

        cr.register("help", new SimpleCommand(CommandCategory.INFO) {
            @Override
            protected void call(Context ctx, String content, String[] args) {
                if (!RatelimitUtils.ratelimit(rateLimiter, ctx, false)) {
                    return;
                }

                if (!ctx.getSelfMember().hasPermission(ctx.getChannel(), Permission.MESSAGE_EMBED_LINKS)) {
                    ctx.sendLocalized("general.missing_embed_permissions");
                    return;
                }

                var commandCategory = CommandCategory.lookupFromString(content);

                if (content.isEmpty()) {
                    buildHelp(ctx, null);
                } else if (commandCategory != null) {
                    buildHelp(ctx, commandCategory);
                } else {
                    var member = ctx.getMember();
                    var command = CommandProcessor.REGISTRY.commands().get(content);

                    if (command == null) {
                        ctx.sendLocalized("commands.help.extended.not_found", EmoteReference.ERROR);
                        return;
                    }

                    if (command.isOwnerCommand() && !CommandPermission.OWNER.test(member)) {
                        ctx.sendLocalized("commands.help.extended.not_found", EmoteReference.ERROR);
                        return;
                    }

                    var help = command.help();
                    if (help == null || help.getDescription() == null) {
                        ctx.sendLocalized("commands.help.extended.no_help", EmoteReference.ERROR);
                        return;
                    }

                    var descriptionList = help.getDescriptionList();
                    var languageContext = ctx.getLanguageContext();

                    var desc = new StringBuilder();
                    if (r.nextBoolean()) {
                        desc.append(languageContext.get("commands.help.patreon"))
                                .append("\n");
                    }

                    if (descriptionList.isEmpty()) {
                        desc.append(help.getDescription());
                    }
                    else {
                        desc.append(descriptionList.get(r.nextInt(descriptionList.size())));
                    }

                    desc.append("\n").append("**Don't include <> or [] on the command itself.**");

                    EmbedBuilder builder = new EmbedBuilder()
                            .setColor(Color.PINK)
                            .setAuthor("Command help for " + content, null,
                                    ctx.getAuthor().getEffectiveAvatarUrl()
                            ).setDescription(desc);

                    if (help.getUsage() != null) {
                        builder.addField(EmoteReference.PENCIL.toHeaderString() + "Usage", help.getUsage(), false);
                    }

                    if (help.getParameters().size() > 0) {
                        builder.addField(EmoteReference.SLIDER.toHeaderString() + "Parameters", help.getParameters().entrySet().stream()
                                        .map(entry -> "`%s` - *%s*".formatted(entry.getKey(), entry.getValue()))
                                        .collect(Collectors.joining("\n")), false
                        );
                    }

                    // Ensure sub-commands show in help.
                    // Only god shall help me now with all of this casting lol.
                    if (command instanceof AliasCommand) {
                        command = ((AliasCommand) command).getCommand();
                    }

                    if (command instanceof ITreeCommand) {
                        var subCommands =
                                ((ITreeCommand) command).getSubCommands()
                                        .entrySet()
                                        .stream()
                                        .sorted(Comparator.comparingInt(a ->
                                                a.getValue().description() == null ? 0 : a.getValue().description().length())
                                        ).collect(
                                        Collectors.toMap(
                                                Map.Entry::getKey, Map.Entry::getValue, (oldValue, newValue) -> oldValue, LinkedHashMap::new
                                        )
                                );

                        var stringBuilder = new StringBuilder();

                        for (var inners : subCommands.entrySet()) {
                            var name = inners.getKey();
                            var inner = inners.getValue();
                            if (inner.isChild()) {
                                continue;
                            }

                            if (inner.description() != null) {
                                stringBuilder.append("""
                                        %s`%s%s` - %s
                                        """.formatted(BLUE_SMALL_MARKER, ctx.getConfig().prefix[0] + content + " ", name, inner.description())
                                );
                            }
                        }

                        if (stringBuilder.length() > 0) {
                            var value = stringBuilder.toString();
                            if (value.length() > 1024) {
                                value = languageContext.get("commands.help.too_long");
                            }

                            builder.addField(EmoteReference.ZAP.toHeaderString() + "Sub-commands", value, false);
                        }
                    }

                    //Known command aliases.
                    var commandAliases = command.getAliases();
                    if (!commandAliases.isEmpty()) {
                        String aliases = commandAliases
                                .stream()
                                .filter(alias -> !alias.equalsIgnoreCase(content))
                                .map("`%s`"::formatted)
                                .collect(Collectors.joining(" "));

                        if (!aliases.trim().isEmpty()) {
                            builder.addField(EmoteReference.FORK.toHeaderString() + "Aliases", aliases, false);
                        }
                    }

                    ctx.send(builder.build(),
                            ActionRow.of(
                                    Button.link("https://wiki.mantaro.site", "Check the wiki!"),
                                    Button.link("https://support.mantaro.site", "Get support here")
                            )
                    );
                }
            }

            @Override
            public HelpContent help() {
                return new HelpContent.Builder()
                        .setDescription("I wonder if this is what you are looking for...")
                        .setDescriptionList(jokes)
                        .setUsage("`~>help <command>`")
                        .addParameter("command", "The command name of the command you want to check information about.")
                        .build();
            }
        });

        cr.registerAlias("help", "commands");
        cr.registerAlias("help", "halp"); //why not
    }

    // Transitional command.
    @Subscribe
    public void slash(CommandRegistry cr) {
        cr.register("slash", new SimpleCommand(CommandCategory.HIDDEN) {
            @Override
            protected void call(Context ctx, String content, String[] args) {
                I18nContext i18nContext = ctx.getLanguageContext();
                var builder = new EmbedBuilder();
                builder.setAuthor(i18nContext.get("commands.slash.title"))
                        .setDescription(i18nContext.get("commands.slash.description").formatted(EmoteReference.WARNING) + "\n" +
                                i18nContext.get("commands.slash.description_2")
                        )
                        .setColor(Color.PINK)
                        .setImage("https://i.imgur.com/LTbSRSV.png")
                        .setFooter(i18nContext.get("commands.pet.status.footer"), ctx.getMember().getEffectiveAvatarUrl());

                ctx.send(builder.build());
            }
        });

        // TODO: port: GameCmds (1), HelpCmd (1), CustomCmds (1), WaifuCmd (1), MusicCmds + MusicUtilCmds (20)
        // Note: How the heck am I gonna do GameCmds? Interactive stuff is hard now.
        cr.registerAlias("slash",
                "info", "status", "shard", "shardinfo", "ping", "time", "prune",
                "ban", "kick", "softban", "userinfo", "serverinfo", "avatar", "roleinfo",
                "support", "donate", "language", "invite", "danbooru",
                "e621", "e926", "yandere", "konachan", "gelbooru", "safebooru", "rule34",
                "iam", "iamnot", "8ball", "createpoll", "anime", "character", "poll", "coinflip",
                "ratewaifu", "roll", "love", "birthday", "profile", "reputation", "equip",
                "unequip", "badges", "activatekey", "premium", "transfer", "itemtransfer", "pet"
        );
    }

    // Transitional command, but with alias information.
    @Subscribe
    public void slashalias(CommandRegistry cr) {
        cr.register("slashalias", new SimpleCommand(CommandCategory.HIDDEN) {
            @Override
            protected void call(Context ctx, String content, String[] args) {
                I18nContext i18nContext = ctx.getLanguageContext();
                var builder = new EmbedBuilder();
                builder.setAuthor(i18nContext.get("commands.slash.title"))
                        .setDescription(i18nContext.get("commands.slash.description_alias").formatted(EmoteReference.WARNING) + "\n" +
                                i18nContext.get("commands.slash.description_2")
                        )
                        .setColor(Color.PINK)
                        .setImage("https://i.imgur.com/LTbSRSV.png")
                        .setFooter(i18nContext.get("commands.pet.status.footer"), ctx.getMember().getEffectiveAvatarUrl());

                ctx.send(builder.build());
            }
        });

        // TODO: port: GameCmds (1), HelpCmd (1), CustomCmds (1), WaifuCmd (1), MusicCmds + MusicUtilCmds (20)
        // Note: How the heck am I gonna do GameCmds? Interactive stuff is hard now.
        cr.registerAlias("slash",
                "guildinfo", "me", "rep", "badge", "vipstatus", "give",
                "transferitem", "transferitems"
        );
    }
}
