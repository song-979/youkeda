package com.youkeda.project.wechatproject.bot.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.router.MessageRouter;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

class MessageHandlerDeliveryTests {

    @Test
    void splitsLongTextOnNaturalBoundariesWithoutLosingContent() {
        String paragraph = "这是一段用于测试微信长消息分片的内容。\n";
        String text = paragraph.repeat(150);

        List<String> chunks = MessageHandler.splitTextForDelivery(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(
                chunk.codePointCount(0, chunk.length()))
                .isLessThanOrEqualTo(MessageHandler.MAX_TEXT_MESSAGE_CODE_POINTS));
        assertThat(String.join("", chunks)).isEqualTo(text);
    }

    @Test
    void keepsEmojiCodePointsIntactWhenHardSplittingLongText() {
        String text = "😀".repeat(MessageHandler.MAX_TEXT_MESSAGE_CODE_POINTS + 25);

        List<String> chunks = MessageHandler.splitTextForDelivery(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.stream().mapToInt(
                chunk -> chunk.codePointCount(0, chunk.length())).sum())
                .isEqualTo(MessageHandler.MAX_TEXT_MESSAGE_CODE_POINTS + 25);
        assertThat(String.join("", chunks)).isEqualTo(text);
    }

    @Test
    void expandsLongMixedReplyIntoTextChunksFollowedByMedia() {
        String text = "长消息内容。".repeat(400);
        ModelReply.ImagePayload image = new ModelReply.ImagePayload(new byte[]{1}, "result.png");

        List<ModelReply> expanded = MessageHandler.expandTextReplies(
                List.of(ModelReply.mixed(text, List.of(image))));

        assertThat(expanded).hasSizeGreaterThan(2);
        assertThat(expanded.subList(0, expanded.size() - 1))
                .allSatisfy(reply -> assertThat(reply.getType()).isEqualTo(ModelReply.Type.TEXT));
        assertThat(expanded.subList(0, expanded.size() - 1).stream()
                .map(ModelReply::getTextContent).collect(Collectors.joining()))
                .isEqualTo(text);
        ModelReply mediaReply = expanded.get(expanded.size() - 1);
        assertThat(mediaReply.getType()).isEqualTo(ModelReply.Type.MIXED);
        assertThat(mediaReply.getTextContent()).isNull();
        assertThat(mediaReply.getImages()).containsExactly(image);
    }

    @Test
    void usesTenSecondDelayBetweenDeliveryMessages() {
        assertThat(MessageHandler.INTER_MESSAGE_DELAY_MS).isEqualTo(10_000L);
    }

    @Test
    void retriesQueuedReplyOnlyAfterInboundContextRefresh() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        MessageHandler handler = new MessageHandler(
                client, mock(MessageBridge.class), mock(MessageRouter.class),
                null, null, null, null, null, null);
        try {
            Method enqueue = MessageHandler.class.getDeclaredMethod(
                    "enqueuePendingDelivery", String.class, List.class);
            enqueue.setAccessible(true);
            enqueue.invoke(handler, "user-1", List.of(ModelReply.text("final result")));

            Field pendingField = MessageHandler.class.getDeclaredField("pendingDeliveries");
            pendingField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Queue<?>> pending = (Map<String, Queue<?>>) pendingField.get(handler);

            Method retry = MessageHandler.class.getDeclaredMethod("retryPendingDeliveries");
            retry.setAccessible(true);
            retry.invoke(handler);
            verifyNoInteractions(client);
            assertThat(pending).containsKey("user-1");

            Field epochsField = MessageHandler.class.getDeclaredField("inboundContextEpochs");
            epochsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Long> epochs = (Map<String, Long>) epochsField.get(handler);
            epochs.put("user-1", 1L);

            Method retryAfterRefresh = MessageHandler.class.getDeclaredMethod(
                    "retryPendingDeliveryAfterContextRefresh", String.class);
            retryAfterRefresh.setAccessible(true);
            retryAfterRefresh.invoke(handler, "user-1");

            verify(client).sendText("user-1", "final result");
            assertThat(pending).doesNotContainKey("user-1");
        } finally {
            handler.destroy();
        }
    }

    @Test
    void serializesConcurrentDeliveryForTheSameUser() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstSendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            firstSendEntered.countDown();
            releaseSend.await(2, TimeUnit.SECONDS);
            active.decrementAndGet();
            return null;
        }).when(client).sendText(anyString(), anyString());

        MessageHandler handler = new MessageHandler(
                client, mock(MessageBridge.class), mock(MessageRouter.class),
                null, null, null, null, null, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Method dispatch = MessageHandler.class.getDeclaredMethod(
                    "dispatch", String.class, List.class);
            dispatch.setAccessible(true);
            Future<?> first = executor.submit(() -> invoke(dispatch, handler, "user-1", "first"));
            assertThat(firstSendEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> invoke(dispatch, handler, "user-1", "second"));

            Thread.sleep(100L);
            assertThat(maxActive).hasValue(1);
            releaseSend.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertThat(maxActive).hasValue(1);
        } finally {
            releaseSend.countDown();
            executor.shutdownNow();
            handler.destroy();
        }
    }

    private static void invoke(Method dispatch, MessageHandler handler,
                               String userId, String text) {
        try {
            dispatch.invoke(handler, userId, List.of(ModelReply.text(text)));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
