package space.subkek.customdiscs.command.subcommand;

import dev.jorel.commandapi.arguments.StringArgument;
import dev.jorel.commandapi.executors.CommandArguments;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import space.subkek.customdiscs.CustomDiscs;
import space.subkek.customdiscs.command.AbstractSubCommand;
import space.subkek.customdiscs.file.CDData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class DeleteSubCommand extends AbstractSubCommand {
  private final CustomDiscs plugin = CustomDiscs.getPlugin();

  public DeleteSubCommand() {
    super("delete");

    this.withFullDescription(getDescription());
    this.withUsage(getSyntax());
    this.withSubcommand(new DeleteListSubCommand());
    this.withArguments(new StringArgument("id"));

    this.executesPlayer(this::executePlayer);
    this.executes(this::execute);
  }

  @Override
  public String getDescription() {
    return plugin.getLanguage().string("command.delete.description");
  }

  @Override
  public String getSyntax() {
    return plugin.getLanguage().string("command.delete.syntax");
  }

  @Override
  public boolean hasPermission(CommandSender sender) {
    return sender.hasPermission("customdiscs.delete");
  }

  @Override
  public void executePlayer(Player player, CommandArguments arguments) {
    if (!hasPermission(player)) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("error.command.no-permission"));
      return;
    }

    String rawId = getArgumentValue(arguments, "id", String.class);
    String discId = normalizeDiscId(rawId);
    if (discId == null) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.error.invalid-id"));
      return;
    }

    CDData.WebDiscRecord record = plugin.getCDData().getWebDisc(discId);
    if (record == null) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.error.not-found", discId));
      return;
    }

    if (!record.owner().equals(player.getUniqueId())) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.error.not-owner", discId));
      return;
    }

    CDData.WebDiscRecord removed = plugin.getCDData().removeWebDisc(discId);
    if (removed == null) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.error.not-found", discId));
      return;
    }
    plugin.getCDData().save();

    boolean deleted = deleteTrackFile(removed.relativePath());
    CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.success", discId));
    if (!deleted) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.file-missing", removed.relativePath()));
    }
  }

  @Override
  public void execute(CommandSender sender, CommandArguments arguments) {
    CustomDiscs.sendMessage(sender, plugin.getLanguage().PComponent("error.command.cant-perform"));
  }

  private String normalizeDiscId(String rawId) {
    if (rawId == null || rawId.isBlank()) {
      return null;
    }

    try {
      return UUID.fromString(rawId.trim()).toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private boolean deleteTrackFile(String relativePath) {
    try {
      Path path = plugin.getLocalTrackStorage().resolveRelativePath(relativePath);
      return Files.deleteIfExists(path);
    } catch (Throwable e) {
      CustomDiscs.error("Failed to remove tracked file '{}': ", e, relativePath);
      return false;
    }
  }
}
