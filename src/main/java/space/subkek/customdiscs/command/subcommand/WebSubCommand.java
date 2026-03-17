package space.subkek.customdiscs.command.subcommand;

import org.bukkit.command.CommandSender;
import space.subkek.customdiscs.CustomDiscs;
import space.subkek.customdiscs.command.AbstractSubCommand;

public class WebSubCommand extends AbstractSubCommand {
  private final CustomDiscs plugin = CustomDiscs.getPlugin();

  public WebSubCommand() {
    super("web");

    this.withFullDescription(getDescription());
    this.withUsage(getSyntax());
    this.withSubcommand(new WebTokenSubCommand());
  }

  @Override
  public String getDescription() {
    return plugin.getLanguage().string("command.web.description");
  }

  @Override
  public String getSyntax() {
    return plugin.getLanguage().string("command.web.syntax");
  }

  @Override
  public boolean hasPermission(CommandSender sender) {
    return sender.hasPermission("customdiscs.web");
  }
}
