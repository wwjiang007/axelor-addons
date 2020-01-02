/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2019 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.redmine.imports.service.issues;

import com.axelor.apps.base.db.AppRedmine;
import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.AppRedmineRepository;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.db.repo.UnitRepository;
import com.axelor.apps.base.service.administration.AbstractBatch;
import com.axelor.apps.hr.db.Timesheet;
import com.axelor.apps.hr.db.TimesheetLine;
import com.axelor.apps.hr.db.repo.TimesheetLineRepository;
import com.axelor.apps.hr.db.repo.TimesheetRepository;
import com.axelor.apps.hr.service.timesheet.TimesheetService;
import com.axelor.apps.project.db.Project;
import com.axelor.apps.project.db.repo.ProjectRepository;
import com.axelor.apps.project.db.repo.TeamTaskCategoryRepository;
import com.axelor.apps.redmine.db.RedmineBatch;
import com.axelor.apps.redmine.imports.service.RedmineImportService;
import com.axelor.apps.redmine.message.IMessage;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.db.JPA;
import com.axelor.exception.service.TraceBackService;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaStore;
import com.axelor.meta.schema.views.Selection.Option;
import com.axelor.team.db.TeamTask;
import com.axelor.team.db.repo.TeamTaskRepository;
import com.google.common.collect.ObjectArrays;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import com.taskadapter.redmineapi.bean.CustomField;
import com.taskadapter.redmineapi.bean.TimeEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedmineImportTimeSpentServiceImpl extends RedmineImportService
    implements RedmineImportTimeSpentService {

  protected TimesheetLineRepository timesheetLineRepo;
  protected TimesheetRepository timesheetRepo;
  protected TimesheetService timesheetService;
  protected UnitRepository unitRepo;

  @Inject
  public RedmineImportTimeSpentServiceImpl(
      UserRepository userRepo,
      ProjectRepository projectRepo,
      ProductRepository productRepo,
      TeamTaskRepository teamTaskRepo,
      TeamTaskCategoryRepository projectCategoryRepo,
      PartnerRepository partnerRepo,
      TimesheetLineRepository timesheetLineRepo,
      TimesheetRepository timesheetRepo,
      TimesheetService timesheetService,
      AppRedmineRepository appRedmineRepo,
      CompanyRepository companyRepo,
      UnitRepository unitRepo) {

    super(
        userRepo,
        projectRepo,
        productRepo,
        teamTaskRepo,
        projectCategoryRepo,
        partnerRepo,
        appRedmineRepo,
        companyRepo);
    this.timesheetLineRepo = timesheetLineRepo;
    this.timesheetRepo = timesheetRepo;
    this.timesheetService = timesheetService;
    this.unitRepo = unitRepo;
  }

  Logger LOG = LoggerFactory.getLogger(getClass());
  protected Long projectId = (long) 0;
  protected Long teamTaskId = (long) 0;
  protected Long userId = (long) 0;
  protected Long productId = (long) 0;
  protected Long defaultCompanyId;
  protected String redmineTimeSpentProductDefault;
  protected BigDecimal redmineTimeSpentDurationForCustomerDefault;
  protected String redmineTimeSpentDurationUnitDefault;

  @Override
  @SuppressWarnings("unchecked")
  public void importTimeSpent(
      List<TimeEntry> redmineTimeEntryList, HashMap<String, Object> paramsMap) {

    if (redmineTimeEntryList != null && !redmineTimeEntryList.isEmpty()) {
      this.onError = (Consumer<Throwable>) paramsMap.get("onError");
      this.onSuccess = (Consumer<Object>) paramsMap.get("onSuccess");
      this.batch = (Batch) paramsMap.get("batch");
      this.errorObjList = (List<Object[]>) paramsMap.get("errorObjList");
      this.lastBatchUpdatedOn = (LocalDateTime) paramsMap.get("lastBatchUpdatedOn");
      this.redmineUserMap = (HashMap<Integer, String>) paramsMap.get("redmineUserMap");
      this.defaultCompanyId = appRedmineRepo.all().fetchOne().getCompany().getId();
      this.selectionMap = new HashMap<>();

      AppRedmine appRedmine = appRedmineRepo.all().fetchOne();
      this.redmineTimeSpentProductDefault = appRedmine.getRedmineTimeSpentProductDefault();
      this.redmineTimeSpentDurationForCustomerDefault =
          appRedmine.getRedmineTimeSpentDurationForCustomerDefault();
      this.redmineTimeSpentDurationUnitDefault =
          appRedmine.getRedmineTimeSpentDurationUnitDefault();

      List<Option> selectionList = new ArrayList<Option>();
      selectionList.addAll(
          MetaStore.getSelectionList("redmine.timesheetline.activity.type.select"));

      ResourceBundle fr = I18n.getBundle(Locale.FRANCE);
      ResourceBundle en = I18n.getBundle(Locale.ENGLISH);

      for (Option option : selectionList) {
        selectionMap.put(fr.getString(option.getTitle()), option.getValue());
        selectionMap.put(en.getString(option.getTitle()), option.getValue());
      }

      Comparator<TimeEntry> compareByDate =
          (TimeEntry o1, TimeEntry o2) -> o1.getSpentOn().compareTo(o2.getSpentOn());
      Collections.sort(redmineTimeEntryList, compareByDate);

      RedmineBatch redmineBatch = batch.getRedmineBatch();
      redmineBatch.setFailedRedmineTimeEntriesIds(null);

      int i = 0;

      for (TimeEntry redmineTimeEntry : redmineTimeEntryList) {
        LOG.debug("Importing time entry: " + redmineTimeEntry.getId());

        errors = new Object[] {};
        String failedRedmineTimeEntriesIds = redmineBatch.getFailedRedmineTimeEntriesIds();

        // ERROR AND DON'T IMPORT IF USER NOT FOUND

        User user = getOsUser(redmineTimeEntry.getUserId());

        if (user != null) {
          this.userId = user.getId();
        } else {
          errors =
              errors.length == 0
                  ? new Object[] {I18n.get(IMessage.REDMINE_IMPORT_USER_NOT_FOUND)}
                  : ObjectArrays.concat(
                      errors,
                      new Object[] {I18n.get(IMessage.REDMINE_IMPORT_USER_NOT_FOUND)},
                      Object.class);

          redmineBatch.setFailedRedmineTimeEntriesIds(
              failedRedmineTimeEntriesIds == null
                  ? redmineTimeEntry.getId().toString()
                  : failedRedmineTimeEntriesIds + "," + redmineTimeEntry.getId().toString());

          setErrorLog(
              I18n.get(IMessage.REDMINE_IMPORT_TIMESHEET_LINE_ERROR),
              redmineTimeEntry.getId().toString());

          fail++;
          continue;
        }

        // ERROR AND DON'T IMPORT IF PROJECT NOT FOUND

        Project project = projectRepo.findByRedmineId(redmineTimeEntry.getProjectId());

        if (project != null) {
          this.projectId = project.getId();
        } else {
          errors =
              errors.length == 0
                  ? new Object[] {I18n.get(IMessage.REDMINE_IMPORT_PROJECT_NOT_FOUND)}
                  : ObjectArrays.concat(
                      errors,
                      new Object[] {I18n.get(IMessage.REDMINE_IMPORT_PROJECT_NOT_FOUND)},
                      Object.class);

          redmineBatch.setFailedRedmineTimeEntriesIds(
              failedRedmineTimeEntriesIds == null
                  ? redmineTimeEntry.getId().toString()
                  : failedRedmineTimeEntriesIds + "," + redmineTimeEntry.getId().toString());

          setErrorLog(
              I18n.get(IMessage.REDMINE_IMPORT_TIMESHEET_LINE_ERROR),
              redmineTimeEntry.getId().toString());

          fail++;
          continue;
        }

        // ERROR AND DON'T IMPORT IF TEAMTASK NOT FOUND

        Integer issueId = redmineTimeEntry.getIssueId();

        if (issueId != null) {
          TeamTask teamTask = teamTaskRepo.findByRedmineId(issueId);

          if (teamTask != null) {
            this.teamTaskId = teamTask.getId();
          } else {
            errors =
                errors.length == 0
                    ? new Object[] {I18n.get(IMessage.REDMINE_IMPORT_TEAM_TASK_NOT_FOUND)}
                    : ObjectArrays.concat(
                        errors,
                        new Object[] {I18n.get(IMessage.REDMINE_IMPORT_TEAM_TASK_NOT_FOUND)},
                        Object.class);

            redmineBatch.setFailedRedmineTimeEntriesIds(
                failedRedmineTimeEntriesIds == null
                    ? redmineTimeEntry.getId().toString()
                    : failedRedmineTimeEntriesIds + "," + redmineTimeEntry.getId().toString());

            setErrorLog(
                I18n.get(IMessage.REDMINE_IMPORT_TIMESHEET_LINE_ERROR),
                redmineTimeEntry.getId().toString());

            fail++;
            continue;
          }
        } else {
          this.teamTaskId = (long) 0;
        }

        // ERROR AND DON'T IMPORT IF PRODUCT IS SELECTED IN REDMINE AND NOT FOUND IN OS

        CustomField redmineProduct = redmineTimeEntry.getCustomField("Product");
        String value =
            redmineProduct != null
                    && redmineProduct.getValue() != null
                    && !redmineProduct.getValue().equals("")
                ? redmineProduct.getValue()
                : (user.getEmployee() != null && user.getEmployee().getProduct() != null
                    ? user.getEmployee().getProduct().getCode()
                    : redmineTimeSpentProductDefault);

        if (value != null) {
          Product product = productRepo.findByCode(value);

          if (product == null) {
            errors =
                errors.length == 0
                    ? new Object[] {I18n.get(IMessage.REDMINE_IMPORT_PRODUCT_NOT_FOUND)}
                    : ObjectArrays.concat(
                        errors,
                        new Object[] {I18n.get(IMessage.REDMINE_IMPORT_PRODUCT_NOT_FOUND)},
                        Object.class);

            redmineBatch.setFailedRedmineTimeEntriesIds(
                failedRedmineTimeEntriesIds == null
                    ? redmineTimeEntry.getId().toString()
                    : failedRedmineTimeEntriesIds + "," + redmineTimeEntry.getId().toString());

            setErrorLog(
                I18n.get(IMessage.REDMINE_IMPORT_TIMESHEET_LINE_ERROR),
                redmineTimeEntry.getId().toString());

            fail++;
            continue;
          }

          this.productId = product.getId();
        } else {
          this.productId = (long) 0;
        }

        try {
          this.createOpenSuiteTimesheetLine(redmineTimeEntry);

          if (errors.length > 0) {
            setErrorLog(
                I18n.get(IMessage.REDMINE_IMPORT_TIMESHEET_LINE_ERROR),
                redmineTimeEntry.getId().toString());
          }
        } finally {
          if (++i % AbstractBatch.FETCH_LIMIT == 0) {
            JPA.em().getTransaction().commit();

            if (!JPA.em().getTransaction().isActive()) {
              JPA.em().getTransaction().begin();
            }

            JPA.clear();

            if (!JPA.em().contains(batch)) {
              batch = JPA.find(Batch.class, batch.getId());
            }
          }
        }
      }

      if (!updatedOnMap.isEmpty()) {
        String values =
            updatedOnMap
                .entrySet()
                .stream()
                .map(
                    entry ->
                        "("
                            + entry.getKey()
                            + ",TO_TIMESTAMP('"
                            + entry.getValue()
                            + "', 'YYYY-MM-DD HH24:MI:SS'))")
                .collect(Collectors.joining(","));

        String query =
            String.format(
                "UPDATE hr_timesheet_line as timesheetline SET updated_on = v.updated_on from (values %s) as v(id,updated_on) where timesheetline.id = v.id",
                values);

        JPA.em().createNativeQuery(query).executeUpdate();
      }
    }

    String resultStr =
        String.format(
            "Redmine SpentTime -> ABS Timesheetline : Success: %d Fail: %d", success, fail);
    result += String.format("%s \n", resultStr);
    LOG.debug(resultStr);
    success = fail = 0;
  }

  @Transactional
  public void createOpenSuiteTimesheetLine(TimeEntry redmineTimeEntry) {

    TimesheetLine timesheetLine = timesheetLineRepo.findByRedmineId(redmineTimeEntry.getId());
    LocalDateTime redmineUpdatedOn =
        redmineTimeEntry
            .getUpdatedOn()
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime();

    if (timesheetLine == null) {
      timesheetLine = new TimesheetLine();
    } else if (lastBatchUpdatedOn != null
        && (redmineUpdatedOn.isBefore(lastBatchUpdatedOn)
            || (timesheetLine.getUpdatedOn().isAfter(lastBatchUpdatedOn)
                && timesheetLine.getUpdatedOn().isAfter(redmineUpdatedOn)))) {
      return;
    }

    this.setTimesheetLineFields(timesheetLine, redmineTimeEntry);

    if (timesheetLine.getTimesheet() != null) {

      try {

        if (timesheetLine.getId() == null) {
          timesheetLine.addCreatedBatchSetItem(batch);
        } else {
          timesheetLine.addUpdatedBatchSetItem(batch);
        }

        timesheetLineRepo.save(timesheetLine);
        updatedOnMap.put(timesheetLine.getId(), redmineUpdatedOn);

        onSuccess.accept(timesheetLine);
        success++;
      } catch (Exception e) {
        onError.accept(e);
        fail++;
        TraceBackService.trace(e, "", batch.getId());
      }
    }
  }

  @Transactional
  public void setTimesheetLineFields(TimesheetLine timesheetLine, TimeEntry redmineTimeEntry) {

    User user = userRepo.find(userId);

    timesheetLine.setUser(user);
    timesheetLine.setRedmineId(redmineTimeEntry.getId());
    timesheetLine.setProject(projectRepo.find(projectId));
    timesheetLine.setTeamTask(teamTaskRepo.find(teamTaskId));
    timesheetLine.setProduct(productRepo.find(productId));
    timesheetLine.setComments(redmineTimeEntry.getComment());

    BigDecimal duration = BigDecimal.valueOf(redmineTimeEntry.getHours());
    timesheetLine.setDuration(duration);
    timesheetLine.setHoursDuration(duration);

    timesheetLine.setDate(
        redmineTimeEntry.getSpentOn().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());

    CustomField customField = redmineTimeEntry.getCustomField("Temps passé ajusté client");
    String value = customField != null ? customField.getValue() : null;
    timesheetLine.setDurationForCustomer(
        value != null && !value.equals("")
            ? new BigDecimal(value)
            : redmineTimeSpentDurationForCustomerDefault);

    customField = redmineTimeEntry.getCustomField("Unité client");
    value = customField != null ? customField.getValue() : null;
    timesheetLine.setDurationUnit(
        value != null && !value.equals("")
            ? unitRepo.findByName(value)
            : unitRepo.findByName(redmineTimeSpentDurationUnitDefault));

    String activityType = redmineTimeEntry.getActivityName();
    if (activityType != null && !activityType.isEmpty()) {
      timesheetLine.setActivityTypeSelect(selectionMap.get(activityType));
    }

    Timesheet timesheet =
        timesheetRepo
            .all()
            .filter(
                "self.user = ?1 AND self.statusSelect != ?2",
                user,
                TimesheetRepository.STATUS_CANCELED)
            .order("-id")
            .fetchOne();

    LocalDate redmineSpentOn =
        redmineTimeEntry.getSpentOn().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

    if (timesheet == null) {
      timesheet = timesheetService.createTimesheet(user, redmineSpentOn, null);
      timesheet.setCompany(
          companyRepo.find(
              user.getActiveCompany() != null
                  ? user.getActiveCompany().getId()
                  : defaultCompanyId));
      timesheet.setToDate(redmineSpentOn);
    } else {
      if (timesheet.getFromDate() == null || timesheet.getFromDate().isAfter(redmineSpentOn)) {
        timesheet.setFromDate(redmineSpentOn);
      }
      if (timesheet.getToDate() == null || timesheet.getToDate().isBefore(redmineSpentOn)) {
        timesheet.setToDate(redmineSpentOn);
      }
    }

    timesheet.setStatusSelect(TimesheetRepository.STATUS_VALIDATED);
    timesheet.setPeriodTotal(timesheet.getPeriodTotal().add(timesheetLine.getHoursDuration()));
    timesheetRepo.save(timesheet);
    timesheetLine.setTimesheet(timesheet);

    setCreatedByUser(timesheetLine, user, "setCreatedBy");
    setLocalDateTime(timesheetLine, redmineTimeEntry.getCreatedOn(), "setCreatedOn");
  }
}