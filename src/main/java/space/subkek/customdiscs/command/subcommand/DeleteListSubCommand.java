package space.subkek.customdiscs.command.subcommand;

import dev.jorel.commandapi.arguments.IntegerArgument;
import dev.jorel.commandapi.executors.CommandArguments;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import space.subkek.customdiscs.CustomDiscs;
import space.subkek.customdiscs.command.AbstractSubCommand;
import space.subkek.customdiscs.file.CDData;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public class DeleteListSubCommand extends AbstractSubCommand {
  private static final int PAGE_SIZE = 10;
  private final CustomDiscs plugin = CustomDiscs.getPlugin();

  public DeleteListSubCommand() {
    super("list");

    this.withFullDescription(getDescription());
    this.withUsage(getSyntax());
    this.withArguments(new IntegerArgument("page", 1).setOptional(true));

    this.executesPlayer(this::executePlayer);
    this.executes(this::execute);
  }

  @Override
  public String getDescription() {
    return plugin.getLanguage().string("command.delete.list.description");
  }

  @Override
  public String getSyntax() {
    return plugin.getLanguage().string("command.delete.list.syntax");
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

    Integer pageArg = arguments.getByClass("page", Integer.class);
    int page = pageArg == null ? 1 : pageArg;

    List<CDData.WebDiscRecord> records = plugin.getCDData().listWebDiscsByOwner(player.getUniqueId());
    if (records.isEmpty()) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.list.empty"));
      return;
    }

    int totalPages = (records.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    if (page > totalPages) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.list.error.page",
        String.valueOf(page),
        String.valueOf(totalPages)));
      return;
    }

    int fromIndex = (page - 1) * PAGE_SIZE;
    int toIndex = Math.min(fromIndex + PAGE_SIZE, records.size());
    List<CDData.WebDiscRecord> pageRecords = records.subList(fromIndex, toIndex);

    DateTimeFormatter formatter;
    try {
      formatter = DateTimeFormatter.ofPattern(plugin.getCDConfig().getDiscLoreDateFormat())
        .withZone(ZoneId.systemDefault());
    } catch (IllegalArgumentException | DateTimeParseException e) {
      formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    }

    CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.list.header",
      String.valueOf(page),
      String.valueOf(totalPages),
      String.valueOf(records.size())));

    for (CDData.WebDiscRecord record : pageRecords) {
      String createdAt = formatter.format(Instant.ofEpochMilli(record.createdAt()));
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.delete.messages.list.entry",
        record.id(),
        record.songName(),
        record.relativePath(),
        createdAt));
    }
  }

  @Override
  public void execute(CommandSender sender, CommandArguments arguments) {
    CustomDiscs.sendMessage(sender, plugin.getLanguage().PComponent("error.command.cant-perform"));
  }
}
