/**
 * ${StudentServiceImpl}
 *
 *  2019 Cognizant Technology Solutions. All Rights Reserved.
 *
 *  This software is the confidential and proprietary information of Cognizant Technology
 *  Solutions("Confidential Information").  You shall not disclose or use Confidential
 *  Information without the express written agreement of Cognizant Technology Solutions.
 *  Modification Log:
 *  -----------------
 *  Date                   Author           Description
 *  02/Mar/2019            371793        Developed base code structure
 *  ---------------------------------------------------------------------------
 */
package com.cognizant.outreach.microservices.school.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import com.cognizant.outreach.entity.ClassDetail;
import com.cognizant.outreach.entity.Student;
import com.cognizant.outreach.entity.StudentSchoolAssoc;
import com.cognizant.outreach.microservices.school.dao.ClassRepository;
import com.cognizant.outreach.microservices.school.dao.MeasurableParamDataRepository;
import com.cognizant.outreach.microservices.school.dao.StudentRepository;
import com.cognizant.outreach.microservices.school.dao.StudentSchoolAssocRepository;
import com.cognizant.outreach.microservices.school.helper.SchoolHelper;
import com.cognizant.outreach.microservices.school.vo.ClassVO;
import com.cognizant.outreach.microservices.school.vo.StudentSearchVO;
import com.cognizant.outreach.microservices.school.vo.StudentVO;
import com.cognizant.outreach.microservices.school.vo.TeamNameCountVO;

/**
 * Service to do crud operation on student details
 * 
 * @author 371793
 */
@Service
public class StudentServiceImpl implements StudentService {

	@Autowired
	StudentSchoolAssocRepository studentSchoolAssocRepository;

	@Autowired
	StudentRepository studentRepository;

	@Autowired
	MeasurableParamDataRepository measurableParamDataRepository;
	
	@Autowired
	ClassRepository classRepository;

	@Override
	public List<TeamNameCountVO> getSchoolTeamList(long schoolId) {
		Optional<List<Object[]>> teamList = studentSchoolAssocRepository.listTeamName(schoolId);
		List<TeamNameCountVO> teamNameCountVOs = new ArrayList<>();
		if (teamList.isPresent()) {
			for (Object[] dbRow : teamList.get()) {
				TeamNameCountVO teamNameCountVO = new TeamNameCountVO();
				teamNameCountVO.setTeamName((String) dbRow[0]);
				teamNameCountVO.setStudentCount(((Long) dbRow[1]).intValue());
				teamNameCountVO.setClassId((Long) dbRow[2]);
				teamNameCountVO.setClassSectionName((String) dbRow[3]+"-"+(String) dbRow[4]);
				teamNameCountVOs.add(teamNameCountVO);
			}
		}
		return teamNameCountVOs;
	}

	@Override
	@Transactional
	public ClassVO saveStudents(ClassVO classVO) {

		if (!CollectionUtils.isEmpty(classVO.getStudentList())) {
			for (StudentVO studentVO : classVO.getStudentList()) {
				// Save student
				if (studentVO.getId() == 0L) {
					// Save student
					Student student = new Student();
					student.setStudentName(studentVO.getStudentName());
					SchoolHelper.addAuditInfo(classVO.getUserId(), student);
					studentRepository.save(student);
					studentVO.setId(student.getId());
					// save associations
					StudentSchoolAssoc association = new StudentSchoolAssoc();
					association.setRollId(classVO.getClassName() + classVO.getSectionName() + student.getId());
					association.setStatus("ACTIVE");
					association.setStudent(student);
					association.setTeamName(studentVO.getTeamName().toLowerCase());
					ClassDetail classDetail = classRepository.findById(classVO.getId()).get();
					association.setClazz(classDetail);
					SchoolHelper.addAuditInfo(classVO.getUserId(), association);
					studentSchoolAssocRepository.save(association);
					studentVO.setAssociationId(association.getId());
				} else {
					// Update student
					Student student = studentRepository.findById(studentVO.getId()).get();
					student.setStudentName(studentVO.getStudentName());
					SchoolHelper.updateAuditInfo(classVO.getUserId(), student);
					studentRepository.save(student);
					StudentSchoolAssoc association = studentSchoolAssocRepository.findById(studentVO.getAssociationId())
							.get();
					association.setTeamName(studentVO.getTeamName().toLowerCase());
					SchoolHelper.updateAuditInfo(classVO.getUserId(), association);
					studentSchoolAssocRepository.save(association);
				}
				deleteStudents(classVO.getStudentList(), classVO.getId());
			}
		}
		return classVO;
	}

	private void deleteStudents(List<StudentVO> studentVOs, long classId) {
		Optional<List<StudentSchoolAssoc>> associations = studentSchoolAssocRepository
				.findClassDetailByClassId(classId);
		if (associations.isPresent()) {
			Map<Long, StudentVO> uiStudentMap = new HashMap<>();
			for (StudentVO studentVO : studentVOs) {
				uiStudentMap.put(studentVO.getAssociationId(), studentVO);
			}

			// If the db association not present in the existing list list the student
			for (StudentSchoolAssoc studentSchoolAssoc : associations.get()) {
				if (null == uiStudentMap.get(studentSchoolAssoc.getId())) {
					measurableParamDataRepository.deleteByStudentSchoolAssocId(studentSchoolAssoc.getId());
					studentSchoolAssocRepository.delete(studentSchoolAssoc);
				}
			}
		}
	}

	@Override
	public byte[] downloadTemplate(StudentSearchVO searchVO) throws IOException {
		// Create Work book
		XSSFWorkbook workbook = new XSSFWorkbook();
		XSSFSheet sheet = workbook.createSheet("Upload Info");

		Row row = sheet.createRow(1);
		Cell headerCell = row.createCell(2);
		headerCell.setCellValue("Introduction");
		headerCell.setCellStyle(this.getIntroductionHeaderCellStyle(workbook));
		CellRangeAddress cellMerge = new CellRangeAddress(1, 1, 2, 3);
		sheet.addMergedRegion(cellMerge);
		setBorder(cellMerge, headerCell, sheet, workbook);
		
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		workbook.write(bos);
		
		return bos.toByteArray();
	}
	
	private CellStyle getIntroductionHeaderCellStyle(Workbook workBook) {

        CellStyle cellStyle = workBook.createCellStyle();

        // To Set Font Style
        Font font = workBook.createFont();
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.BLACK.getIndex());
        font.setBold(true);
        cellStyle.setFont(font);

        cellStyle.setAlignment((short)HorizontalAlignment.CENTER.ordinal());
        cellStyle.setVerticalAlignment((short)VerticalAlignment.CENTER.ordinal());
        
        return cellStyle;
	}
	
	private void setBorder(CellRangeAddress region, Cell cell, XSSFSheet sheet, XSSFWorkbook workbook) {
		
		RegionUtil.setBorderBottom(1, region, sheet, workbook);
		RegionUtil.setBorderTop(1, region, sheet, workbook);
		RegionUtil.setBorderLeft(1, region, sheet, workbook);
		RegionUtil.setBorderRight(1, region, sheet, workbook);
				
		CellStyle cellStyle = cell.getCellStyle();
		cellStyle.setFillBackgroundColor(IndexedColors.WHITE.getIndex());
	}
	
	private CellStyle getIntroductionValueItemsCellStyle(Workbook workBook) {

        CellStyle cellStyle = workBook.createCellStyle();

        // To Set Font Style
        Font font = workBook.createFont();
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.BLACK.getIndex());
        cellStyle.setFont(font);

        cellStyle.setAlignment((short)HorizontalAlignment.LEFT.ordinal());
        cellStyle.setVerticalAlignment((short)VerticalAlignment.CENTER.ordinal());
        
        return cellStyle;
	}

	@Override
	public ResponseEntity<ClassVO> uploadStudentData(MultipartFile file, String userId) {
		// TODO Auto-generated method stub
		return null;
	}
}