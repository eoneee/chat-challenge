package com.challenge.chat.domain.chat.service;

import com.challenge.chat.domain.chat.dto.ChatDto;
import com.challenge.chat.domain.chat.dto.ChatRoomDto;
import com.challenge.chat.domain.chat.dto.response.ChatSearchResponse;
import com.challenge.chat.domain.chat.entity.Chat;
import com.challenge.chat.domain.chat.entity.ChatES;
import com.challenge.chat.domain.chat.entity.ChatRoom;
import com.challenge.chat.domain.chat.entity.MemberChatRoom;
import com.challenge.chat.domain.chat.entity.MessageType;
import com.challenge.chat.domain.chat.repository.ChatRepository;
import com.challenge.chat.domain.chat.repository.ChatRoomRepository;
import com.challenge.chat.domain.chat.repository.ChatSearchRepository;
import com.challenge.chat.domain.chat.repository.MemberChatRoomRepository;
import com.challenge.chat.domain.member.entity.Member;
import com.challenge.chat.domain.member.service.MemberService;
import com.challenge.chat.exception.RestApiException;
import com.challenge.chat.exception.dto.ChatErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

	private final MemberChatRoomRepository memberChatRoomRepository;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatRepository chatRepository;
	private final ChatSearchRepository chatSearchRepository;
	private final MemberService memberService;
	private final ElasticsearchOperations elasticsearchOperations;

	@Transactional
	public ChatRoomDto makeChatRoom(final String roomName, final String memberEmail) {
		log.info("Service : 채팅방 생성");

		ChatRoom chatRoom = ChatRoom.of(roomName);
		Member member = memberService.findMemberByEmail(memberEmail);

		// TODO : 비동기적으로 chatRoom 과 memberChatRoom을 저장하기
		chatRoomRepository.save(chatRoom);
		memberChatRoomRepository.save(MemberChatRoom.of(chatRoom, member));

		return ChatRoomDto.from(chatRoom);
	}

	@Transactional
	public ChatRoomDto registerChatRoom(final String roomCode, final String memberEmail) {
		log.info("Service : 채팅방 추가");

		ChatRoom chatRoom = findChatRoom(roomCode);
		Member member = memberService.findMemberByEmail(memberEmail);
		memberChatRoomRepository.save(MemberChatRoom.of(chatRoom, member));

		return ChatRoomDto.from(chatRoom);
	}

	@Transactional(readOnly = true)
	public List<ChatRoomDto> searchChatRoomList(final String memberEmail) {
		log.info("Service : 채팅방 리스트 조회");

		Member member = memberService.findMemberByEmail(memberEmail);
		List<MemberChatRoom> memberChatRoomList = findChatRoomByMember(member);

		return memberChatRoomList
			.stream()
			.map(a -> ChatRoomDto.from(a.getRoom()))
			.collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public List<ChatDto> searchChatList(final String roomCode, final String memberEmail) {
		log.info("Service : 채팅방 메세지 조회");

		ChatRoom chatRoom = findChatRoom(roomCode);

		return chatRepository
			.findByRoom(chatRoom)
			.stream()
			.sorted(Comparator.comparing(Chat::getCreatedAt))
			.map(ChatDto::from)
			.collect(Collectors.toList());
	}

	public ChatDto makeEnterMessageAndSetSessionAttribute(ChatDto chatDto, SimpMessageHeaderAccessor headerAccessor) {
		log.info("Service : 채팅방 입장");

		// socket session에 사용자의 정보 저장
		try {
			Objects.requireNonNull(headerAccessor.getSessionAttributes()).put("email", chatDto.getEmail());
			headerAccessor.getSessionAttributes().put("roomCode", chatDto.getRoomCode());
			headerAccessor.getSessionAttributes().put("nickname", chatDto.getNickname());
		} catch (Exception e) {
			throw new RestApiException(ChatErrorCode.SOCKET_CONNECTION_ERROR);
		}

		chatDto.setMessage(chatDto.getNickname() + "님 입장!! ο(=•ω＜=)ρ⌒☆");

		return chatDto;
	}

	@Transactional
	public void sendChatRoom(ChatDto chatDto) {
		log.info("Service : 채팅 보내기 - {}", chatDto.getMessage());

		Member member = memberService.findMemberByEmail(chatDto.getEmail());
		ChatRoom room = findChatRoom(chatDto.getRoomCode());

		// MySQL 저장
		chatRepository.save(Chat.of(chatDto.getType(), member, room, chatDto.getMessage()));
		// ElasticSearch 저장
		chatSearchRepository.save(ChatES.of(
			chatDto.getType(),
			chatDto.getNickname(),
			chatDto.getEmail(),
			chatDto.getRoomCode(),
			chatDto.getMessage()
		));
	}

	public List<ChatSearchResponse> findChatList(final String roomCode, final String message, final Pageable pageable) {
		log.info("Service: 채팅 검색하기 - message: {}, roomCode: {}", message, roomCode);

		QueryBuilder queryBuilder = QueryBuilders.boolQuery()
			.must(QueryBuilders.matchQuery("message", message).analyzer("korean"))
			.must(QueryBuilders.matchQuery("roomCode", roomCode));

		NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
			.withQuery(queryBuilder)
			.withPageable(pageable)
			.build();

		SearchHits<ChatES> searchHits = elasticsearchOperations.search(searchQuery, ChatES.class);

		return searchHits.stream()
			.map(SearchHit::getContent)
			.map(ChatSearchResponse::from)
			.collect(Collectors.toList());
	}

	public ChatDto leaveChatRoom(SimpMessageHeaderAccessor headerAccessor) {
		log.info("Service 채팅방 나가기");

		String roomCode = (String)headerAccessor.getSessionAttributes().get("roomCode");
		String nickName = (String)headerAccessor.getSessionAttributes().get("nickName");
		String userId = (String)headerAccessor.getSessionAttributes().get("userId");

		return ChatDto.builder()
			.type(MessageType.LEAVE)
			.roomCode(roomCode)
			.nickname(nickName)
			.email(userId)
			.message(nickName + "님 퇴장!! ヽ(*。>Д<)o゜")
			.build();
	}

	public ChatRoom findChatRoom(String roomCode) {
		return chatRoomRepository.findByRoomCode(roomCode).orElseThrow(
			() -> new RestApiException(ChatErrorCode.CHATROOM_NOT_FOUND));
	}

	private List<MemberChatRoom> findChatRoomByMember(Member member) {
		return memberChatRoomRepository.findByMember(member).orElseThrow(
			() -> new RestApiException(ChatErrorCode.CHATROOM_NOT_FOUND));
	}
}