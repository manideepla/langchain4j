package dev.langchain4j.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class StreamingAiServicesIT {

    StreamingChatLanguageModel streamingChatModel
            = OpenAiStreamingChatModel.withApiKey(System.getenv("OPENAI_API_KEY"));

    interface Assistant {

        TokenStream chat(String userMessage);
    }

    @Test
    void should_stream_answer() throws Exception {

        Assistant assistant = AiServices.create(Assistant.class, streamingChatModel);

        StringBuilder answerBuilder = new StringBuilder();
        CompletableFuture<String> future = new CompletableFuture<>();

        assistant.chat("What is the capital of Germany?")
                .onNext(answerBuilder::append)
                .onComplete(() -> future.complete(answerBuilder.toString()))
                .onError(future::completeExceptionally)
                .start();

        String answer = future.get(30, SECONDS);

        assertThat(answer).contains("Berlin");
    }

    @Test
    void should_stream_answers_with_memory() throws Exception {

        ChatMemory chatMemory = MessageWindowChatMemory.withCapacity(10);

        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(streamingChatModel)
                .chatMemory(chatMemory)
                .build();


        String firstUserMessage = "Hi, my name is Klaus";
        StringBuilder firstAnswerBuilder = new StringBuilder();
        CompletableFuture<String> firstFuture = new CompletableFuture<>();

        assistant.chat(firstUserMessage)
                .onNext(firstAnswerBuilder::append)
                .onComplete(() -> firstFuture.complete(firstAnswerBuilder.toString()))
                .onError(firstFuture::completeExceptionally)
                .start();

        String firstAnswer = firstFuture.get(30, SECONDS);
        assertThat(firstAnswer).contains("Klaus");


        String secondUserMessage = "What is my name?";
        StringBuilder secondAnswerBuilder = new StringBuilder();
        CompletableFuture<String> secondFuture = new CompletableFuture<>();

        assistant.chat(secondUserMessage)
                .onNext(secondAnswerBuilder::append)
                .onComplete(() -> secondFuture.complete(secondAnswerBuilder.toString()))
                .onError(secondFuture::completeExceptionally)
                .start();

        String secondAnswer = secondFuture.get(30, SECONDS);
        assertThat(secondAnswer).contains("Klaus");


        List<ChatMessage> messages = chatMemory.messages();
        assertThat(messages).hasSize(4);

        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(0).text()).isEqualTo(firstUserMessage);

        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
        assertThat(messages.get(1).text()).isEqualTo(firstAnswer);

        assertThat(messages.get(2)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(2).text()).isEqualTo(secondUserMessage);

        assertThat(messages.get(3)).isInstanceOf(AiMessage.class);
        assertThat(messages.get(3).text()).isEqualTo(secondAnswer);
    }

    static class Calculator {

        @Tool
        double squareRoot(double number) {
            return Math.sqrt(number);
        }
    }

    @Test
    void should_execute_tool_then_stream_answer() throws Exception {

        Calculator calculator = spy(new Calculator());

        ChatMemory chatMemory = MessageWindowChatMemory.withCapacity(10);

        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(streamingChatModel)
                .chatMemory(chatMemory)
                .tools(calculator)
                .build();

        StringBuilder answerBuilder = new StringBuilder();
        CompletableFuture<String> future = new CompletableFuture<>();

        String userMessage = "What is the square root of 485906798473894056 in scientific notation?";
        assistant.chat(userMessage)
                .onNext(answerBuilder::append)
                .onComplete(() -> future.complete(answerBuilder.toString()))
                .onError(future::completeExceptionally)
                .start();

        String answer = future.get(30, SECONDS);

        assertThat(answer).contains("6.97");


        verify(calculator).squareRoot(485906798473894056.0);
        verifyNoMoreInteractions(calculator);


        List<ChatMessage> messages = chatMemory.messages();
        assertThat(messages).hasSize(4);

        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(0).text()).isEqualTo(userMessage);

        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
        AiMessage aiMessage = (AiMessage) messages.get(1);
        assertThat(aiMessage.toolExecutionRequest().name()).isEqualTo("squareRoot");
        assertThat(aiMessage.toolExecutionRequest().arguments())
                .isEqualToIgnoringWhitespace("{\"arg0\": 485906798473894056}");
        assertThat(messages.get(1).text()).isNull();

        assertThat(messages.get(2)).isInstanceOf(ToolExecutionResultMessage.class);
        assertThat(messages.get(2).text()).isEqualTo("6.97070153193991E8");

        assertThat(messages.get(3)).isInstanceOf(AiMessage.class);
        assertThat(messages.get(3).text()).contains("6.97");
    }
}