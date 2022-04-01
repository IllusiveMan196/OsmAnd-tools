package net.osmand.server.assist;

import net.osmand.server.assist.data.UserChatIdentifier;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public abstract class AssistantConversation {

	private long time;
	protected UserChatIdentifier chatIdentifier;

	public AssistantConversation(UserChatIdentifier chatIdentifier) {
		this.chatIdentifier = chatIdentifier;
		this.time = System.currentTimeMillis();
	}

	public UserChatIdentifier getChatIdentifier() {
		return chatIdentifier;
	}

	public long getUpdateTime() {
		return time;
	}
	
	protected boolean validateEmptyInput(OsmAndAssistantBot bot, String msg) throws TelegramApiException {
		if(msg.isEmpty()) {
			bot.sendMethod(getSendMessage("Your input is not valid. Please try again:"));
			return false;
		}
		return true;
	}
	
	protected SendMessage getSendMessage(String text) {
		return new SendMessage(chatIdentifier.getChatId()+"", text);
	}


	/**
	 * @return name of conversation to be displayed to the user
	 */
	public abstract String getConversationId();
	

	/**
	 * @param bot
	 * @param msg
	 * @return true if conversation has finished
	 */
	public abstract boolean updateMessage(OsmAndAssistantBot bot, Message msg, String reply) throws TelegramApiException;
	
	public boolean updateMessage(OsmAndAssistantBot bot, Message msg) throws TelegramApiException {
		return updateMessage(bot, msg, msg.getText());
	}
}
