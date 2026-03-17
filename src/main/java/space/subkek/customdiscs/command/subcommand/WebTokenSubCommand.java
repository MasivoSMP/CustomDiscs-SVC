package space.subkek.customdiscs.command.subcommand;

import dev.jorel.commandapi.executors.CommandArguments;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import space.subkek.customdiscs.CustomDiscs;
import space.subkek.customdiscs.command.AbstractSubCommand;
import space.subkek.customdiscs.web.UploadTokenService;

public class WebTokenSubCommand extends AbstractSubCommand {
  private final CustomDiscs plugin = CustomDiscs.getPlugin();

  public WebTokenSubCommand() {
    super("token");

    this.withFullDescription(getDescription());
    this.withUsage(getSyntax());

    this.executesPlayer(this::executePlayer);
    this.executes(this::execute);
  }

  @Override
  public String getDescription() {
    return plugin.getLanguage().string("command.web.token.description");
  }

  @Override
  public String getSyntax() {
    return plugin.getLanguage().string("command.web.token.syntax");
  }

  @Override
  public boolean hasPermission(CommandSender sender) {
    return sender.hasPermission("customdiscs.web.token");
  }

  @Override
  public void executePlayer(Player player, CommandArguments arguments) {
    if (!hasPermission(player)) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("error.command.no-permission"));
      return;
    }

    if (!plugin.getCDConfig().isWebEnabled()) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.web.messages.disabled"));
      return;
    }

    int limit = plugin.getLocalDiscService().resolveWebCreationLimit(player);
    int currentCount = plugin.getLocalDiscService().countPlayerWebDiscs(player.getUniqueId());
    if (limit >= 0 && currentCount >= limit) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.create.messages.error.limit-reached",
        String.valueOf(currentCount),
        String.valueOf(limit)));
      return;
    }

    UploadTokenService.CreatedToken token = plugin.getUploadTokenService()
      .createToken(player.getUniqueId(), plugin.getCDConfig().getWebTokenTtlSeconds());
    String uploadUrl = plugin.getWebServerManager().buildUploadUrl(token.token(), player.getName(), token.expiresAt());

    CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.web.token.messages.success", uploadUrl));
    CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.web.token.messages.expiry",
      String.valueOf(Math.max(plugin.getCDConfig().getWebTokenTtlSeconds(), 1))));
  }

  @Override
  public void execute(CommandSender sender, CommandArguments arguments) {
    CustomDiscs.sendMessage(sender, plugin.getLanguage().PComponent("error.command.cant-perform"));
  }
}
