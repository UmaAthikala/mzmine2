/*
 * Copyright 2006-2008 The MZmine Development Team
 * 
 * This file is part of MZmine.
 * 
 * MZmine is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * MZmine is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * MZmine; if not, write to the Free Software Foundation, Inc., 51 Franklin St,
 * Fifth Floor, Boston, MA 02110-1301 USA
 */

package net.sf.mzmine.modules.peakpicking.threestep.peakconstruction.simpleconnector;

import java.util.Arrays;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.Vector;

import net.sf.mzmine.data.Peak;
import net.sf.mzmine.data.PeakStatus;
import net.sf.mzmine.data.RawDataFile;
import net.sf.mzmine.data.Scan;
import net.sf.mzmine.modules.peakpicking.twostep.massdetection.MzPeak;
import net.sf.mzmine.modules.peakpicking.twostep.peakconstruction.ConnectedMzPeak;
import net.sf.mzmine.modules.peakpicking.twostep.peakconstruction.ConnectedPeak;
import net.sf.mzmine.modules.peakpicking.twostep.peakconstruction.MatchScore;
import net.sf.mzmine.modules.peakpicking.twostep.peakconstruction.PeakBuilder;
import net.sf.mzmine.util.MathUtils;

/**
 * This class implements a simple peak builder. This takes all detected MzPeaks
 * in one Scan and try to find a possible relationship between each one of these
 * with MzPeaks of the previous scan. This relationship is set by a match score
 * using MatchScore class, according with the parameters of Tolerance of MZ and
 * Intensity. Also it can apply a second search for possible peaks (threshold
 * level), over a already detected peak.
 * 
 */
public class SimpleConnector implements PeakBuilder {

	private float intTolerance, mzTolerance, chromatographicThresholdLevel;
	private float minimumPeakHeight, minimumPeakDuration;
	private boolean chromatographicFilter;
	private Vector<ConnectedPeak> underConstructionPeaks;

	public SimpleConnector(SimpleConnectorParameters parameters) {
		intTolerance = (Float) parameters
				.getParameterValue(SimpleConnectorParameters.intTolerance);
		minimumPeakDuration = (Float) parameters
				.getParameterValue(SimpleConnectorParameters.minimumPeakDuration);
		minimumPeakHeight = (Float) parameters
				.getParameterValue(SimpleConnectorParameters.minimumPeakHeight);
		mzTolerance = (Float) parameters
				.getParameterValue(SimpleConnectorParameters.mzTolerance);
		chromatographicThresholdLevel = (Float) parameters
				.getParameterValue(SimpleConnectorParameters.chromatographicThresholdLevel);
		chromatographicFilter = (Boolean) parameters
				.getParameterValue(SimpleConnectorParameters.chromatographicFilter);
		underConstructionPeaks = new Vector<ConnectedPeak>();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see net.sf.mzmine.modules.peakpicking.twostep.peakconstruction.PeakBuilder#addScan(net.sf.mzmine.data.Scan,
	 *      net.sf.mzmine.modules.peakpicking.twostep.massdetection.MzPeak[],
	 *      net.sf.mzmine.data.RawDataFile)
	 */
	public Peak[] addScan(Scan scan, MzPeak[] mzValues, RawDataFile dataFile) {

		Vector<Peak> finishedPeaks = new Vector<Peak>();
		Vector<ConnectedMzPeak> cMzPeaks = new Vector<ConnectedMzPeak>();

		// Calculate scores between MzPeaks
		TreeSet<MatchScore> scores = new TreeSet<MatchScore>();

		// Convert MzPeak in ConnectedMzPeak to deal with status property
		// (boolean connected)
		for (MzPeak mzPeak : mzValues)
			cMzPeaks.add(new ConnectedMzPeak(scan, mzPeak));

		// Calculate score for each ConnectedMzPeak
		for (ConnectedPeak ucPeak : underConstructionPeaks) {
			for (ConnectedMzPeak currentMzPeak : cMzPeaks) {
				MatchScore score = new MatchScore(ucPeak, currentMzPeak,
						mzTolerance, intTolerance);

				if (score.getScore() < Float.MAX_VALUE) {
					scores.add(score);
				}
			}
		}

		// Connect the best scoring pairs of under-construction and 1d peaks

		Iterator<MatchScore> scoreIterator = scores.iterator();
		while (scoreIterator.hasNext()) {
			MatchScore score = scoreIterator.next();

			// If ConnectedMzPeak is already connected, then move to next score
			ConnectedMzPeak cMzPeak = score.getMzPeak();
			if (cMzPeak.isConnected()) {
				continue;
			}

			// If ConnectedMzPeak is growing, then move on to next score
			ConnectedPeak ucPeak = score.getPeak();
			if (ucPeak.isGrowing()) {
				continue;
			}

			// Add MzPeak to the proper Peak and set status connected
			ucPeak.addMzPeak(cMzPeak);
			cMzPeak.setConnected();
		}

		// Check if there are any under-construction peaks that were not
		// connected (finished)

		Iterator<ConnectedPeak> iteratorConPeak = underConstructionPeaks
				.iterator();
		while (iteratorConPeak.hasNext()) {

			ConnectedPeak ucPeak = iteratorConPeak.next();
			// If nothing was added,
			if (!ucPeak.isGrowing()) {

				// Finalize peak
				ucPeak.finalizedAddingDatapoints(PeakStatus.DETECTED);

				// Apply second criteria to identify possible peaks.
				if (chromatographicFilter) {

					Peak[] chromatographicPeaks = chromatographicPeaksSearch(ucPeak);

					if (chromatographicPeaks.length != 0) {
						for (Peak p : chromatographicPeaks) {
							float pLength = p.getRawDataPointsRTRange()
									.getSize();
							float pHeight = p.getHeight();
							if ((pLength >= minimumPeakDuration)
									&& (pHeight >= minimumPeakHeight)) {
								finishedPeaks.add(p);
							}
						}
					}

				} else {

					// Check length of detected peak (filter according to set
					// parameters)

					float ucLength = ucPeak.getRawDataPointsRTRange().getSize();
					float ucHeight = ucPeak.getHeight();
					if ((ucLength >= minimumPeakDuration)
							&& (ucHeight >= minimumPeakHeight)) {
						finishedPeaks.add(ucPeak);
					}
				}

				// Remove the peak from under construction peaks
				iteratorConPeak.remove();

			} else
				ucPeak.resetGrowingState();
		}

		// If there are some unconnected MzPeaks, then start a new
		// under-construction peak for each of them

		for (ConnectedMzPeak cMzPeak : cMzPeaks) {
			if (!cMzPeak.isConnected()) {
				ConnectedPeak ucPeak = new ConnectedPeak(dataFile, cMzPeak);
				underConstructionPeaks.add(ucPeak);
			}

		}

		return finishedPeaks.toArray(new Peak[0]);
	}

	/**
	 * 
	 * Verify a detected peak using the criteria of chromatographic threshold
	 * level. If some regions of the peak, do not comply with the criteria, are
	 * excluded. And besides if there are more than one region that cover this
	 * criteria and they are separated, we construct a different peak for each
	 * region.
	 * 
	 * @param ConnectedPeak
	 *            ucPeak
	 * @return Peak[]
	 */
	private Peak[] chromatographicPeaksSearch(ConnectedPeak ucPeak) {

		// MzPeak[] mzValues = ucPeak.getMzPeaks().toArray(new MzPeak[0]);
		ConnectedMzPeak[] mzValues = ucPeak.getConnectedMzPeaks();

		float[] intensities = new float[mzValues.length];

		for (int i = 0; i < intensities.length; i++)
			intensities[i] = mzValues[i].getMzPeak().getIntensity();
		Arrays.sort(intensities);

		float chromatographicThresholdlevelPeak = MathUtils.calcQuantile(
				intensities, chromatographicThresholdLevel);

		Vector<ConnectedPeak> newChromatoPeaks = new Vector<ConnectedPeak>();
		Vector<ConnectedMzPeak> newChromatoMzPeaks = new Vector<ConnectedMzPeak>();

		for (ConnectedMzPeak mzPeak : mzValues) {

			// If the intensity of this MzPeak is bigger than threshold level
			// we store it in a Vector.

			if (mzPeak.getMzPeak().getIntensity() >= chromatographicThresholdlevelPeak) {
				newChromatoMzPeaks.add(mzPeak);
			}

			// If the intensity of lower than threshold level, it could mean
			// that is the ending of this new threshold level peak
			// we store it in a Vector.

			else {

				// Verify if we add some MzPeaks to the new ConnectedPeak, if
				// that is true, we create a new ConnectedPeak with all stored
				// MzPeaks.

				if (newChromatoMzPeaks.size() > 0) {
					ConnectedPeak chromatoPeak = new ConnectedPeak(ucPeak
							.getDataFile(), newChromatoMzPeaks.elementAt(0));
					for (int i = 1; i < newChromatoMzPeaks.size(); i++) {
						chromatoPeak.addMzPeak(newChromatoMzPeaks.elementAt(i));
					}
					newChromatoMzPeaks.clear();
					chromatoPeak.finalizedAddingDatapoints(PeakStatus.DETECTED);
					newChromatoPeaks.add(chromatoPeak);
				}
			}
		}

		// At least we verify if there is one last threshold peak at the end,
		// and it was not detected in the for cycle, due there is not MzPeak
		// with intensity below of threshold level to define the ending

		if (newChromatoMzPeaks.size() > 0) {
			ConnectedPeak chromatoPeak = new ConnectedPeak(
					ucPeak.getDataFile(), newChromatoMzPeaks.elementAt(0));
			for (int i = 1; i < newChromatoMzPeaks.size(); i++) {
				chromatoPeak.addMzPeak(newChromatoMzPeaks.elementAt(i));
			}
			chromatoPeak.finalizedAddingDatapoints(PeakStatus.DETECTED);
			newChromatoPeaks.add(chromatoPeak);
		}

		return newChromatoPeaks.toArray(new ConnectedPeak[0]);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see net.sf.mzmine.modules.peakpicking.twostep.peakconstruction.PeakBuilder#finishPeaks()
	 */
	public Peak[] finishPeaks() {
		Vector<Peak> finishedPeaks = new Vector<Peak>();
		for (ConnectedPeak ucPeak : underConstructionPeaks) {
			// Finalize peak
			ucPeak.finalizedAddingDatapoints(PeakStatus.DETECTED);

			// Check length & height
			float ucLength = ucPeak.getRawDataPointsRTRange().getSize();
			float ucHeight = ucPeak.getHeight();

			if ((ucLength >= minimumPeakDuration)
					&& (ucHeight >= minimumPeakHeight)) {

				if (chromatographicFilter) {

					Peak[] chromatographicPeaks = chromatographicPeaksSearch(ucPeak);

					if (chromatographicPeaks.length != 0) {
						for (Peak p : chromatographicPeaks) {
							float pLength = p.getRawDataPointsRTRange()
									.getSize();
							float pHeight = p.getHeight();
							if ((pLength >= minimumPeakDuration)
									&& (pHeight >= minimumPeakHeight)) {
								finishedPeaks.add(p);
							}
						}
					}

				} else
					finishedPeaks.add(ucPeak);
			}
		}
		return finishedPeaks.toArray(new Peak[0]);
	}

}
