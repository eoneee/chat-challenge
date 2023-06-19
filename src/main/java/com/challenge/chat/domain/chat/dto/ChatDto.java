package com.challenge.chat.domain.chat.dto;

import com.challenge.chat.domain.chat.entity.Chat;
// import com.challenge.chat.domain.chat.entity.ChatES;
import com.challenge.chat.domain.chat.entity.MessageType;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatDto {

	private MessageType type;
	private String nickname;
	private String email;
	private String roomCode;
	private String message;
	private Instant createdAt;

	public static ChatDto from(Chat chat) {
		return new ChatDto(
			chat.getType(),
			chat.getNickname(),
			chat.getEmail(),
			chat.getRoomId(),
			chat.getMessage(),
			chat.getCreatedAt()
		);
	}

	public static Chat toEntity(ChatDto chatDto) {
		return Chat.of(
			chatDto.getType(),
			chatDto.getNickname(),
			chatDto.getEmail(),
			chatDto.getRoomCode(),
			chatDto.getMessage()
		);
	}
}