package space.subkek.customdiscs.command.subcommand;

import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.StringArgument;
import dev.jorel.commandapi.arguments.TextArgument;
import dev.jorel.commandapi.executors.CommandArguments;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import space.subkek.customdiscs.CustomDiscs;
import space.subkek.customdiscs.command.AbstractSubCommand;
import space.subkek.customdiscs.service.LocalDiscService;

public class LocalCreateSubCommand extends AbstractSubCommand {
  private final CustomDiscs plugin = CustomDiscs.getPlugin();

  public LocalCreateSubCommand() {
    super("local");

    this.withFullDescription(getDescription());
    this.withUsage(getUsage());

    this.withArguments(new StringArgument("filename").replaceSuggestions(ArgumentSuggestions.stringCollection((sender) ->
      this.plugin.getLocalTrackStorage().listTrackFiles())));
    this.withArguments(new TextArgument("song_name").replaceSuggestions(quotedArgument(null)));

    this.executesPlayer(this::executePlayer);
    this.executes(this::execute);
  }

  @Override
  public String getDescription() {
    return plugin.getLanguage().string("command.create.local.description");
  }

  @Override
  public String getSyntax() {
    return plugin.getLanguage().string("command.create.local.syntax");
  }

  @Override
  public boolean hasPermission(CommandSender sender) {
    return sender.hasPermission("customdiscs.create.local");
  }

  @SuppressWarnings("UnstableApiUsage")
  @Override
  public void executePlayer(Player player, CommandArguments arguments) {
    if (!hasPermission(player)) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("error.command.no-permission"));
      return;
    }

    String filename = getArgumentValue(arguments, "filename", String.class);
    String customName = getArgumentValue(arguments, "song_name", String.class);

    try {
      plugin.getLocalDiscService().applyToHeldDisc(player, filename, customName);
    } catch (LocalDiscService.LocalDiscException e) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent(e.getFailure().getLanguageKey()));
      return;
    }

    CustomDiscs.sendMessage(player, plugin.getLanguage().component("command.create.messages.file", filename));
    CustomDiscs.sendMessage(player, plugin.getLanguage().component("command.create.messages.name", customName));
  }

  @Override
  public void execute(CommandSender sender, CommandArguments arguments) {
    CustomDiscs.sendMessage(sender, plugin.getLanguage().PComponent("error.command.cant-perform"));
  }
}
