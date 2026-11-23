package com.tasks.organizer.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasks.organizer.attempt.repository.AttemptQuestionRepository;
import com.tasks.organizer.attempt.repository.AttemptRepository;
import com.tasks.organizer.authentication.dto.request.SigninRequest;
import com.tasks.organizer.authentication.dto.response.JwtAuthenticationResponse;
import com.tasks.organizer.authentication.service.AuthenticationService;
import com.tasks.organizer.organization.OrganizationRepository;
import com.tasks.organizer.organization.entity.Organization;
import com.tasks.organizer.organizationmember.OrganizationMemberRepository;
import com.tasks.organizer.organizationmember.entity.OrgPermissions;
import com.tasks.organizer.organizationmember.entity.OrgRoles;
import com.tasks.organizer.organizationmember.entity.OrganizationMember;
import com.tasks.organizer.question.AnswerRepository;
import com.tasks.organizer.question.QuestionRepository;
import com.tasks.organizer.question.entity.Answer;
import com.tasks.organizer.question.entity.Question;
import com.tasks.organizer.section.SectionRepository;
import com.tasks.organizer.section.entity.Section;
import com.tasks.organizer.support.OrgHelper;
import com.tasks.organizer.support.TopicHelper;
import com.tasks.organizer.topic.TopicRepository;
import com.tasks.organizer.topic.entity.Topic;
import com.tasks.organizer.topic.entity.Visibility;
import com.tasks.organizer.topic.entity.Topic.TopicType;
import com.tasks.organizer.user.UserRepository;
import com.tasks.organizer.user.entity.Role;
import com.tasks.organizer.user.entity.User;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;


@SpringBootTest 
@AutoConfigureMockMvc 
@Transactional 
@Import({OrgHelper.class, TopicHelper.class})
@ExtendWith(OutputCaptureExtension.class)
public class OrganizationTopicOwnershipTest {
  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired AttemptRepository attemptRepository;
  @Autowired TopicRepository topicRepository;
  @Autowired QuestionRepository questionRepository;
  @Autowired SectionRepository sectionRepository;
  @Autowired AnswerRepository answerRepository;
  @Autowired AttemptQuestionRepository attemptQuestionRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired AuthenticationService authenticationService;
  @Autowired EntityManager entityManager;
  @Autowired OrganizationRepository organizationRepository;
  @Autowired OrganizationMemberRepository organizationMemberRepository;
  @Autowired ObjectMapper objectMapper;
  @Autowired OrgHelper orgHelper;
  @Autowired TopicHelper topicHelper;

  Answer answer;
  User owner, intruder;
  Section section;
  String ownerToken, intruderToken;
  OrganizationMember orgAdmin;
  Organization organization;
  Topic topic;

  String generateTopicPayload(Section currentSection) {
    return """
      {
        "topic": {
          "name": "Chapter 5 Biology Quiz",
          "description": "Covers cell structure and function",
          "topicType": "QUIZ",
          "visibility": "PRIVATE",
          "dueDate": "2026-12-03T18:30:00",
          "isActive": true,
          "questionPoolSize": 1,
          "sectionId": "%s"
        },
        "questions": [
          {
            "points": 1.0,
            "question": "Which organelle produces most of the cell's ATP?",
            "answer": "Mitochondria",
            "answers": [
              { "answer": "Mitochondria", "isCorrect": true }
            ]
          }
        ]
      }
      """.formatted(currentSection.getId());
  }

  String generateTopicUpdatePayload(Topic detachedTopic) throws Exception {
    // The builder-created topic from setUp never has its questions populated, so reload it
    Topic topic = topicRepository.findById(detachedTopic.getId()).orElseThrow();
    List<Map<String, Object>> questions = new ArrayList<>();

    for (Question q : topic.getQuestions()) {
      List<Map<String, Object>> answers = new ArrayList<>();

      for (Answer a : q.getAnswers()) {
        Map<String, Object> answerMap = new LinkedHashMap<>();
        answerMap.put("id", a.getId());
        answerMap.put("answer", a.getAnswer());
        answerMap.put("isCorrect", a.getIsCorrect());
        answerMap.put("isActive", a.isActive());
        answers.add(answerMap);
      }

      Map<String, Object> questionMap = new LinkedHashMap<>();
      questionMap.put("id", q.getId());
      questionMap.put("points", q.getPoints());
      questionMap.put("question", q.getQuestion());
      questionMap.put("answer", q.getAnswer());
      questionMap.put("imageUrl", q.getImageUrl());
      questionMap.put("isActive", q.getIsActive());
      questionMap.put("answers", answers);
      questions.add(questionMap);
    }

    // Only the fields declared on TopicDTO
    Map<String, Object> topicMap = new LinkedHashMap<>();
    topicMap.put("name", topic.getName());
    topicMap.put("description", topic.getDescription());
    topicMap.put("topicType", topic.getTopicType());
    topicMap.put("visibility", topic.getVisibility());
    topicMap.put("dueDate", topic.getDueDate());
    topicMap.put("isActive", topic.getIsActive());
    topicMap.put("questionPoolSize", topic.getQuestionPoolSize());
    // Read the section from the caller's (detached) topic so tests can point the update at a different section
    topicMap.put("sectionId", detachedTopic.getSection().getId());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("topic", topicMap);
    body.put("questions", questions);

    return objectMapper.writeValueAsString(body);
  }

  @BeforeEach
  void setUp() {
    owner = userRepository.save(User.builder()
      .email("owner@test.com")
      .password(passwordEncoder.encode("12"))
      .firstName("owner")
      .lastName("owner")
      .role(Role.USER)
      .build()
    );

    organization = Organization.builder()
      .name("good org")
      .description("good org")
      .maxOrgAdmins(1)
      .maxStudents(1)
      .maxTeachers(1)
    .build();

    organization = organizationRepository.save(organization);

    orgAdmin = OrganizationMember.builder()
      .organization(organization)
      .role(OrgRoles.ORG_ADMIN)
      .user(owner)
      .permissions(new OrgPermissions[0])
      .build();

    organizationMemberRepository.save(orgAdmin);

    intruder = userRepository.save(User.builder()
      .email("intruder@test.com")
      .password(passwordEncoder.encode("12"))
      .firstName("intruder")
      .lastName("intruder")
      .role(Role.USER)
      .build()
    );

    section = sectionRepository.save(Section.builder()
      .name("test section description")
      .organization(organization)
      .description("test section description")
      .build()
    );

    topic = topicRepository.save(Topic.builder()
      .name("Test topic")
      .section(section)
      .description("Test description")
      .topicType(TopicType.RANDOM_QUESTIONS)
      .questionPoolSize(1)
      .organization(organization)
      .user(owner)
      .visibility(Visibility.PRIVATE)
    .build());

    Question question = questionRepository.save(Question.builder()
      .question("test question")
      .points(1)
      .topic(topic)
    .build());

    answer = answerRepository.save(
      Answer.builder()
        .answer("TEst answer")
        .isCorrect(true)
        .question(question)
      .build()
    );

    question.setAnswers(List.of(answer));

    SigninRequest intruderRequest = SigninRequest.builder()
    .email(intruder.getEmail())
    .password("12")
    .build();
    JwtAuthenticationResponse intruderRes = authenticationService.signin(intruderRequest);
    
    SigninRequest ownerRequest = SigninRequest.builder()
    .email(owner.getEmail())
    .password("12")
    .build();
    JwtAuthenticationResponse ownerRes = authenticationService.signin(ownerRequest);

    intruderToken = intruderRes.getToken();
    ownerToken = ownerRes.getToken();
    entityManager.persist(organization);
    entityManager.persist(topic);
    entityManager.flush();
    entityManager.clear();
  }

  @Test 
  void outsiderCannotAccessPrivateTopic() throws Exception {
    mockMvc.perform(get("/api/authenticated/topics")
      .param("topicId", topic.getId().toString())
      .header("Authorization", "Bearer " + intruderToken))
      .andExpect(status().isNotFound());
  }

  @Test 
  void crossOrganizationAccessTopic() throws Exception {
    OrganizationMember otherOrgMember = orgHelper.createOrgWithMember(intruder, OrgRoles.TEACHER, OrgPermissions.CREATE_TOPIC);
    String authHeader = orgHelper.getOrgHeader(intruderToken, otherOrgMember.getOrganization().getId());

    mockMvc.perform(get("/api/authenticated/topics")
      .param("topicId", topic.getId().toString())
      .header("Authorization", "Bearer " + intruderToken)
      .header("Org-Authorization", authHeader))
    .andExpect(status().isNotFound());
  }

  @Test 
  void adminWithCorrectAnswers() throws Exception {
    String orgHeader = orgHelper.getOrgHeader(ownerToken, organization.getId());

    mockMvc.perform(get("/api/authenticated/topics")
      .param("topicId", topic.getId().toString())
      .header("Authorization", "Bearer " + ownerToken)
      .header("Org-Authorization", orgHeader))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.questions").isNotEmpty())
    .andExpect(jsonPath("$.data.questions[*].id").exists())
    .andExpect(jsonPath("$.data.questions[*].answers[*].id").exists())
    .andExpect(jsonPath("$.data.questions[*].answers[*].answer").exists())
    .andExpect(jsonPath("$.data.questions[*].answers[*].correct").exists()); //Ensure student in an org never receives correct answer
  }

  @Test 
  void studentWithNoCorrectAnswer() throws Exception {
    OrganizationMember newMember = orgHelper.addMember(organization, intruder, OrgRoles.STUDENT, true, null);
    String authHeader = orgHelper.getOrgHeader(intruderToken, newMember.getOrganization().getId());

    mockMvc.perform(get("/api/authenticated/topics")
      .param("topicId", topic.getId().toString())
      .header("Authorization", "Bearer " + intruderToken)
      .header("Org-Authorization", authHeader))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.questions").isNotEmpty())
    .andExpect(jsonPath("$.data.questions[*].id").exists())
    .andExpect(jsonPath("$.data.questions[*].answers[*].id").exists())
    .andExpect(jsonPath("$.data.questions[*].answers[*].answer").exists())
    .andExpect(jsonPath("$.data.questions[*].answers[*].correct").doesNotExist()); //Ensure student in an org never receives correct answer
  }

  @Test 
  void canViewPublicOrgTopic() throws Exception {
    Topic managed = topicRepository.findById(topic.getId()).orElseThrow();
    managed.setVisibility(Visibility.PUBLIC);
    topicRepository.saveAndFlush(managed);

    mockMvc.perform(get("/api/authenticated/topics")
      .param("topicId", topic.getId().toString())
      .header("Authorization", "Bearer " + intruderToken))
      .andExpect(status().isOk());
  }

  @Test
  void teacherWithoutCreatePermissionDenied() throws Exception {
    String payload = generateTopicPayload(section);

    OrganizationMember otherOrgMember = orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, true, OrgPermissions.UPDATE_TOPIC);
    String authHeader = orgHelper.getOrgHeader(intruderToken, otherOrgMember.getOrganization().getId());
    
    long topicCount = topicRepository.count();

    mockMvc.perform(post("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .header("Org-Authorization", authHeader)
        .param("sectionId", section.getId().toString())
        .param("organizationId", organization.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNotFound());
    
    assertTrue(topicCount == topicRepository.count());
  }

  @Test
  void teacherWithCreatePermissionAllowed() throws Exception {
    String payload = generateTopicPayload(section);

    OrganizationMember otherOrgMember = orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, true, OrgPermissions.CREATE_TOPIC);
    String authHeader = orgHelper.getOrgHeader(intruderToken, otherOrgMember.getOrganization().getId());

    long topicCount = topicRepository.count();

    mockMvc.perform(post("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .header("Org-Authorization", authHeader)
        .param("sectionId", section.getId().toString())
        .param("organizationId", organization.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isCreated());

    assertTrue(topicCount+1 == topicRepository.count());
  }

  @Test
  void inactiveTeacherCannotCreateTopic() throws Exception {
    String payload = generateTopicPayload(section);

    OrganizationMember newMember = orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, false, OrgPermissions.CREATE_TOPIC);
    String authHeader = orgHelper.getOrgHeader(intruderToken, newMember.getOrganization().getId());

    long topicCount = topicRepository.count();

    mockMvc.perform(post("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .header("Org-Authorization", authHeader)
        .param("sectionId", section.getId().toString())
        .param("organizationId", newMember.getOrganization().getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNotFound());
    
    assertTrue(topicCount == topicRepository.count());
  }

  @Test
  void outsiderCannotUpdateOrgTopic() throws Exception {
    String payload = generateTopicUpdatePayload(topic);
    
    mockMvc.perform(patch("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .param("topicId", topic.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNotFound());
  }

  @Test 
  void teacherWithoutUpdatePermissionDenied() throws Exception {
    String payload = generateTopicUpdatePayload(topic);
    
    OrganizationMember newMember = orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, true, OrgPermissions.CREATE_TOPIC);
    String authHeader = orgHelper.getOrgHeader(intruderToken, newMember.getOrganization().getId());

    mockMvc.perform(patch("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .header("Org-Authorization", authHeader)
        .param("topicId", topic.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNotFound());
  }

  @Test
  void teacherWithUpdatePermissionAllowed() throws Exception {
    String payload = generateTopicPayload(section);

    OrganizationMember newMember = orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, true, OrgPermissions.UPDATE_TOPIC);
    String authHeader = orgHelper.getOrgHeader(intruderToken, newMember.getOrganization().getId());

    mockMvc.perform(patch("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .header("Org-Authorization", authHeader)
        .param("topicId", topic.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNoContent());
  }

  @Test
  void inactiveTeacherCannotUpdateTopic() throws Exception {
    String payload = generateTopicPayload(section);

    OrganizationMember newMember = orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, false, OrgPermissions.UPDATE_TOPIC);
    String authHeader = orgHelper.getOrgHeader(intruderToken, newMember.getOrganization().getId());

    mockMvc.perform(patch("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .header("Authorization", authHeader)
        .param("topicId", topic.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNotFound());
  }

  @Test
  void outsiderCannotDeleteTopic() throws Exception {
    mockMvc.perform(delete("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .param("topicId", topic.getId().toString()))
      .andExpect(status().isNotFound());
    
    assertTrue(topicRepository.findById(topic.getId()).isPresent());
  }

  @Test
  void teacherWithoutDeletePermissionsDenied() throws Exception {
    OrganizationMember newMember = orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, true, OrgPermissions.CREATE_TOPIC);
    String authHeader = orgHelper.getOrgHeader(intruderToken, newMember.getOrganization().getId());
  
    mockMvc.perform(delete("/api/authenticated/topics")
      .header("Authorization", "Bearer " + intruderToken)
      .header("Org-Authorization", authHeader)
      .param("topicId", topic.getId().toString()))
    .andExpect(status().isNotFound());

    // Deny means nothing changed
    assertTrue(topicRepository.findById(topic.getId()).isPresent());
  }

  @Test
  void teacherWithDeletePermissionsAllowed() throws Exception {
      OrganizationMember newMember = orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, true, OrgPermissions.DELETE_TOPIC);
      String authHeader = orgHelper.getOrgHeader(intruderToken, newMember.getOrganization().getId());

      mockMvc.perform(delete("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .header("Org-Authorization", authHeader)
        .param("topicId", topic.getId().toString()))
      .andExpect(status().isNoContent());

    // Deny means nothing changed
    assertTrue(topicRepository.findById(topic.getId()).isEmpty());
  }

  @Test
  void inactiveTeacherCannotDeleteTopic() throws Exception {
      OrganizationMember newMember = orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, false, OrgPermissions.DELETE_TOPIC);
      String authHeader = orgHelper.getOrgHeader(intruderToken, newMember.getOrganization().getId());

      mockMvc.perform(delete("/api/authenticated/topics")
        .header("Authorization", "Bearer " + intruderToken)
        .header("Org-Authorization", authHeader)
        .param("topicId", topic.getId().toString()))
      .andExpect(status().isNotFound());

    // Deny means nothing changed
    assertTrue(topicRepository.findById(topic.getId()).isPresent());
  }

  @Test 
  void cannotUpdateTopicSectionBelongingToAnotherOrg(CapturedOutput output) throws Exception {
    Organization otherOrg = orgHelper.createOrganization("Other org");
    Section otherOrgSection = topicHelper.createSection("Other org section", otherOrg, intruder);

    topic.setSection(otherOrgSection);
    String payload = generateTopicUpdatePayload(topic);

    String orgHeader = orgHelper.getOrgHeader(ownerToken, organization.getId());

    mockMvc.perform(patch("/api/authenticated/topics")
        .header("Authorization", "Bearer " + ownerToken)
        .header("Org-Authorization", orgHeader)
        .param("topicId", topic.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.message").value("Topic not found"));

    assertTrue(output.getOut().contains("Section does not belong to authenticated user"));

    // The topic must still belong to its original section
    entityManager.clear();
    Topic reloaded = topicRepository.findById(topic.getId()).orElseThrow();
    assertTrue(reloaded.getSection().getId().equals(section.getId()));
  }

  @Test 
  void cannotCreateTopicWithSectionBelongingToAnotherOrg(CapturedOutput output) throws Exception {
    Organization otherOrg = orgHelper.createOrganization("Other org");
    Section otherOrgSection = topicHelper.createSection("Other org section", otherOrg, intruder);

    String payload = generateTopicPayload(otherOrgSection);
    String orgHeader = orgHelper.getOrgHeader(ownerToken, organization.getId());

    long topicCount = topicRepository.count();

    mockMvc.perform(post("/api/authenticated/topics")
        .header("Authorization", "Bearer " + ownerToken)
        .header("Org-Authorization", orgHeader)
        .param("sectionId", otherOrgSection.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNotFound());

    assertTrue(output.getOut().contains("Section does not belong to authenticated user"));

    // No topic should have been created
    assertTrue(topicCount == topicRepository.count());
  }

  @Test
  void cannotMovePersonalTopicIntoOrgSection(CapturedOutput output) throws Exception {
    // Personal topic (organization = null) owned by the org admin, in their own personal section
    Topic personalTopic = topicHelper.createTopic(owner, Visibility.PUBLIC, TopicType.RANDOM_QUESTIONS);
    Section personalSection = personalTopic.getSection();

    // Detach so setSection below only changes the payload, not the DB row
    entityManager.flush();
    entityManager.clear();

    personalTopic.setSection(section);
    String payload = generateTopicUpdatePayload(personalTopic);

    String orgHeader = orgHelper.getOrgHeader(ownerToken, organization.getId());

    // No Org-Authorization header: this is a personal topic
    mockMvc.perform(patch("/api/authenticated/topics")
        .header("Authorization", "Bearer " + ownerToken)
        .header("Org-Authorization", orgHeader)
        .param("topicId", personalTopic.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNotFound());

    assertTrue(output.getOut().contains("Section does not belong to authenticated user"));

    // The topic must still be in its personal section with no organization
    entityManager.clear();
    Topic reloaded = topicRepository.findById(personalTopic.getId()).orElseThrow();
    assertTrue(reloaded.getSection().getId().equals(personalSection.getId()));
    assertTrue(reloaded.getOrganization() == null);
  }

  // Reloads the topic from the DB and asserts it still points at the expected section
  void assertTopicStillInSection(UUID topicId, UUID expectedSectionId) {
    entityManager.clear();
    Topic reloaded = topicRepository.findById(topicId).orElseThrow();
    assertTrue(reloaded.getSection().getId().equals(expectedSectionId));
  }

  // Sends a topic update for the given payload with an optional org header
  ResultActions patchTopic(String userToken, String orgHeader, UUID topicId, String payload) throws Exception {
    MockHttpServletRequestBuilder request = patch("/api/authenticated/topics")
      .header("Authorization", "Bearer " + userToken)
      .param("topicId", topicId.toString())
      .contentType(MediaType.APPLICATION_JSON)
      .content(payload);

    if (orgHeader != null) {
      request.header("Org-Authorization", orgHeader);
    }

    return mockMvc.perform(request);
  }

  @Test
  void cannotMoveOrgTopicIntoPersonalSection(CapturedOutput output) throws Exception {
    // A personal section (no org) owned by the org admin, so the user-ownership check alone would pass
    Section personalSection = topicHelper.createSection("Owner personal section", null, owner);

    // topic is detached after setUp's clear(), so this only changes the payload
    topic.setSection(personalSection);
    String payload = generateTopicUpdatePayload(topic);

    String orgHeader = orgHelper.getOrgHeader(ownerToken, organization.getId());

    patchTopic(ownerToken, orgHeader, topic.getId(), payload)
      .andExpect(status().isNotFound());

    assertTrue(output.getOut().contains("Section does not belong to authenticated user"));
    assertTopicStillInSection(topic.getId(), section.getId());
  }

  @Test
  void cannotCreateOrgTopicInPersonalSection(CapturedOutput output) throws Exception {
    // The user owns this section personally, but the topic would be created under the org
    Section personalSection = topicHelper.createSection("Owner personal section", null, owner);

    String payload = generateTopicPayload(personalSection);
    String orgHeader = orgHelper.getOrgHeader(ownerToken, organization.getId());

    long topicCount = topicRepository.count();

    mockMvc.perform(post("/api/authenticated/topics")
        .header("Authorization", "Bearer " + ownerToken)
        .header("Org-Authorization", orgHeader)
        .param("sectionId", personalSection.getId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
      .andExpect(status().isNotFound());

    assertTrue(output.getOut().contains("Section does not belong to authenticated user"));
    assertTrue(topicCount == topicRepository.count());
  }

  @Test
  void memberOfBothOrgsCannotMoveTopicToOtherOrgSectionUsingTopicOrgHeader(CapturedOutput output) throws Exception {
    // intruder can update topics in both orgs
    Organization otherOrg = orgHelper.createOrganization("Other org");
    orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, true, OrgPermissions.UPDATE_TOPIC);
    orgHelper.addMember(otherOrg, intruder, OrgRoles.TEACHER, true, OrgPermissions.UPDATE_TOPIC);
    Section otherOrgSection = topicHelper.createSection("Other org section", otherOrg, intruder);

    topic.setSection(otherOrgSection);
    String payload = generateTopicUpdatePayload(topic);

    // Header for the topic's org: update permission passes, so the section check must reject it
    String topicOrgHeader = orgHelper.getOrgHeader(intruderToken, organization.getId());

    patchTopic(intruderToken, topicOrgHeader, topic.getId(), payload)
      .andExpect(status().isNotFound());

    assertTrue(output.getOut().contains("Section does not belong to authenticated user"));
    assertTopicStillInSection(topic.getId(), section.getId());
  }

  @Test
  void memberOfBothOrgsCannotMoveTopicToOtherOrgSectionUsingSectionOrgHeader() throws Exception {
    // intruder can update topics in both orgs
    Organization otherOrg = orgHelper.createOrganization("Other org");
    orgHelper.addMember(organization, intruder, OrgRoles.TEACHER, true, OrgPermissions.UPDATE_TOPIC);
    orgHelper.addMember(otherOrg, intruder, OrgRoles.TEACHER, true, OrgPermissions.UPDATE_TOPIC);
    Section otherOrgSection = topicHelper.createSection("Other org section", otherOrg, intruder);

    topic.setSection(otherOrgSection);
    String payload = generateTopicUpdatePayload(topic);

    // Header for the section's org: the section check would pass, so the topic permission check must reject it
    String sectionOrgHeader = orgHelper.getOrgHeader(intruderToken, otherOrg.getId());

    patchTopic(intruderToken, sectionOrgHeader, topic.getId(), payload)
      .andExpect(status().isNotFound());

    assertTopicStillInSection(topic.getId(), section.getId());
  }

}
