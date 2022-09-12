package com.rushi.betterreadsdataloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.cassandra.CqlSessionBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.rushi.betterreadsdataloader.author.Author;
import com.rushi.betterreadsdataloader.author.AuthorRepository;
import com.rushi.betterreadsdataloader.book.Book;
import com.rushi.betterreadsdataloader.book.BookRepository;

import connection.DataStaxAstraProperties;

@SpringBootApplication
@EnableConfigurationProperties(DataStaxAstraProperties.class)
public class BetterreadsDataLoaderApplication {

	@Autowired
	AuthorRepository authorRepository;

	@Autowired
	BookRepository bookRepository;

	@Value("${datadump.location.author}")
	private String authorDumpLocation;

	@Value("${datadump.location.works}")
	private String worksDumpLocation;

	public static void main(String[] args) {
		SpringApplication.run(BetterreadsDataLoaderApplication.class, args);
	}

	public void initAuthors() {

		Path path = Paths.get(authorDumpLocation);
		try (Stream<String> lines = Files.lines(path)) {
			lines.forEach(line -> {
				// Read and parse the line
				String jsonString = line.substring(line.indexOf("{"));

				try {
					JSONObject jsonObject = new JSONObject(jsonString);

					// Construct Author object
					Author author = new Author();
					author.setName(jsonObject.optString("name"));
					author.setPersonalName(jsonObject.optString("personal_name"));
					author.setId(jsonObject.optString("key").replace("/authors/", ""));

					// Persist using Repository
					System.out.println("Saving authors" + author.getName() + "...");
					authorRepository.save(author);
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void initWorks() {

		Path path = Paths.get(worksDumpLocation);
		DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

		try (Stream<String> lines = Files.lines(path)) {
			lines.forEach(line -> {
				// Read and parse the line
				String jsonString = line.substring(line.indexOf("{"));

				try {
					JSONObject jsonObject = new JSONObject(jsonString);

					// Construct Book object
					Book book = new Book();
					book.setId(jsonObject.getString("key").replace("/works/", ""));
					book.setName(jsonObject.optString("title"));
					JSONObject descriptionObj = jsonObject.optJSONObject("description");
					if (descriptionObj != null) {
						book.setDescription(descriptionObj.optString("value"));
					}
					JSONObject publishedObj = jsonObject.optJSONObject("created");
					if (publishedObj != null) {
						String dateStr = publishedObj.getString("value");
						book.setPublishedData(LocalDate.parse(dateStr, dateFormat));
					}

					JSONArray coversJsonArr = jsonObject.optJSONArray("covers");
					if (coversJsonArr != null) {
						List<String> coverIds = new ArrayList<>();

						for (int i = 0; i < coversJsonArr.length(); i++) {
							coverIds.add(coversJsonArr.getString(i));
						}
						book.setCoverIds(coverIds);
					}

					JSONArray authoJsonArr = jsonObject.optJSONArray("authors");
					if (authoJsonArr != null) {
						List<String> authorsIds = new ArrayList<>();
						for (int i = 0; i < authoJsonArr.length(); i++) {
							String authorId = authoJsonArr.getJSONObject(i).getJSONObject("author").getString("key")
									.replace("/authors/", "");
							authorsIds.add(authorId);
						}
						book.setAuthorId(authorsIds);

						List<String> authorNames = authorsIds.stream().map(id -> authorRepository.findById(id))
								.map(optionalAuthor -> {
									if (!optionalAuthor.isPresent())
										return "Unknown Author";
									return optionalAuthor.get().getName();
								}).collect(Collectors.toList());

						book.setAuthorNames(authorNames);
					}

					bookRepository.save(book);

				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@PostConstruct
	public void start() {

		initAuthors();
		initWorks();
	}

	@Bean
	public CqlSessionBuilderCustomizer sessionBuilderCustomizer(DataStaxAstraProperties astraProperties) {

		Path bundle = astraProperties.getSecureConnectBundle().toPath();
		return builder -> builder.withCloudSecureConnectBundle(bundle);
	}
}
