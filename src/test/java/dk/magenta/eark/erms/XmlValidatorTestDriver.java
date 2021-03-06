package dk.magenta.eark.erms;

import java.io.FileNotFoundException;
import java.io.InputStream;

import dk.magenta.eark.erms.ead.MappingParser;
import dk.magenta.eark.erms.xml.XmlValidator;

public class XmlValidatorTestDriver {

	public static void main(String[] args) throws FileNotFoundException {
		
		InputStream in = MappingParser.class.getClassLoader().getResourceAsStream("mapping.xml");
		
		XmlValidator validator = new XmlValidator();
		System.out.println(validator.isXmlValid(in));
	}

}
