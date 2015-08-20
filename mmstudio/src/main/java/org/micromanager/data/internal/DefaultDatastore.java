///////////////////////////////////////////////////////////////////////////////
//PROJECT:       Micro-Manager
//SUBSYSTEM:     Data API implementation
//-----------------------------------------------------------------------------
//
// AUTHOR:       Chris Weisiger, 2015
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

package org.micromanager.data.internal;

import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.DatastoreFrozenException;
import org.micromanager.data.Image;
import org.micromanager.data.Storage;
import org.micromanager.data.SummaryMetadata;
import org.micromanager.data.internal.DefaultSummaryMetadata;
import org.micromanager.data.internal.multipagetiff.StorageMultipageTiff;
import org.micromanager.events.internal.DefaultEventManager;
import org.micromanager.internal.MMStudio;
import org.micromanager.internal.utils.FileDialogs;
import org.micromanager.internal.utils.PrioritizedEventBus;
import org.micromanager.internal.utils.ReportingUtils;


public class DefaultDatastore implements Datastore {
   private Storage storage_ = null;
   private PrioritizedEventBus bus_;
   private boolean isFrozen_ = false;
   private String savePath_ = null;

   public DefaultDatastore() {
      bus_ = new PrioritizedEventBus();
   }

   /**
    * Copy all data from the provided other Datastore into ourselves.
    */
   public void copyFrom(Datastore alt) {
      try {
         setSummaryMetadata(alt.getSummaryMetadata());
         for (Coords coords : alt.getUnorderedImageCoords()) {
            putImage(alt.getImage(coords));
         }
      }
      catch (DatastoreFrozenException e) {
         ReportingUtils.logError("Can't copy to datastore: we're frozen");
      }
      catch (IllegalArgumentException e) {
         ReportingUtils.logError("Inconsistent image coordinates in datastore");
      }
   }

   @Override
   public void setStorage(Storage storage) {
      storage_ = storage;
   }

   /**
    * Registers objects at default priority levels.
    */
   @Override
   public void registerForEvents(Object obj) {
      registerForEvents(obj, 100);
   }

   public void registerForEvents(Object obj, int priority) {
      bus_.register(obj, priority);
   }

   @Override
   public void unregisterForEvents(Object obj) {
      bus_.unregister(obj);
   }

   @Override
   public void publishEvent(Object obj) {
      bus_.post(obj);
   }

   @Override
   public Image getImage(Coords coords) {
      if (storage_ != null) {
         return storage_.getImage(coords);
      }
      return null;
   }

   @Override
   public Image getAnyImage() {
      if (storage_ != null) {
         return storage_.getAnyImage();
      }
      return null;
   }

   @Override
   public List<Image> getImagesMatching(Coords coords) {
      if (storage_ != null) {
         return storage_.getImagesMatching(coords);
      }
      return null;
   }

   @Override
   public Iterable<Coords> getUnorderedImageCoords() {
      if (storage_ != null) {
         return storage_.getUnorderedImageCoords();
      }
      return null;
   }

   @Override
   public void putImage(Image image) throws DatastoreFrozenException, IllegalArgumentException {
      if (isFrozen_) {
         throw new DatastoreFrozenException();
      }
      // Check for validity of axes.
      Coords coords = image.getCoords();
      List<String> ourAxes = getAxes();
      // Can get null axes if we have no storage yet, which we should handle
      // gracefully.
      if (ourAxes != null && ourAxes.size() > 0) {
         for (String axis : coords.getAxes()) {
            if (!ourAxes.contains(axis)) {
               throw new IllegalArgumentException("Invalid image coordinate axis " + axis + "; allowed axes are " + ourAxes);
            }
         }
      }

      // Track changes to our axes so we can note the axis order.
      SummaryMetadata summary = getSummaryMetadata();
      ArrayList<String> axisOrderList = null;
      if (summary != null) {
         String[] axisOrder = summary.getAxisOrder();
         if (axisOrder == null) {
            axisOrderList = new ArrayList<String>();
         }
         else {
            axisOrderList = new ArrayList<String>(Arrays.asList(axisOrder));
         }
      }

      bus_.post(new NewImageEvent(image, this));

      if (summary != null) {
         boolean didAdd = false;
         for (String axis : coords.getAxes()) {
            if (!axisOrderList.contains(axis) && coords.getIndex(axis) > 0) {
               // This axis is newly nonzero.
               axisOrderList.add(axis);
               didAdd = true;
            }
         }
         if (didAdd) {
            // Update summary metadata.
            summary = summary.copy().axisOrder(
                  axisOrderList.toArray(new String[] {})).build();
            setSummaryMetadata(summary);
         }
      }
   }

   @Override
   public Integer getMaxIndex(String axis) {
      if (storage_ != null) {
         return storage_.getMaxIndex(axis);
      }
      return -1;
   }

   @Override
   public Integer getAxisLength(String axis) {
      return getMaxIndex(axis) + 1;
   }

   @Override
   public List<String> getAxes() {
      if (storage_ != null) {
         return storage_.getAxes();
      }
      return null;
   }

   @Override
   public Coords getMaxIndices() {
      if (storage_ != null) {
         return storage_.getMaxIndices();
      }
      return null;
   }

   @Override
   public SummaryMetadata getSummaryMetadata() {
      if (storage_ == null) {
         return null;
      }
      SummaryMetadata result = storage_.getSummaryMetadata();
      if (result == null) {
         // Provide an empty summary metadata instead.
         result = (new DefaultSummaryMetadata.Builder()).build();
      }
      return result;
   }
   
   @Override
   public void setSummaryMetadata(SummaryMetadata metadata) throws DatastoreFrozenException {
      if (isFrozen_) {
         throw new DatastoreFrozenException();
      }
      bus_.post(new NewSummaryMetadataEvent(metadata));
   }

   @Override
   public void freeze() {
      bus_.post(new DefaultDatastoreFrozenEvent());
      isFrozen_ = true;
   }

   @Override
   public boolean getIsFrozen() {
      return isFrozen_;
   }

   @Override
   public void close() {
      DefaultEventManager.getInstance().post(
            new DefaultDatastoreClosingEvent(this));
   }

   @Override
   public void setSavePath(String path) {
      savePath_ = path;
      bus_.post(new DefaultDatastoreSavedEvent(path));
   }

   @Override
   public String getSavePath() {
      return savePath_;
   }

   @Override
   public boolean save(Datastore.SaveMode mode, Window window) {
      File file = FileDialogs.save(window,
            "Please choose a location for the data set", MMStudio.MM_DATA_SET);
      if (file == null) {
         return false;
      }
      return save(mode, file.getAbsolutePath());
   }

   // TODO: re-use existing file-based storage if possible/relevant (i.e.
   // if our current Storage is a file-based Storage).
   @Override
   public boolean save(Datastore.SaveMode mode, String path) {
      SummaryMetadata summary = getSummaryMetadata();
      if (summary == null) {
         // Create dummy summary metadata just for saving.
         summary = (new DefaultSummaryMetadata.Builder()).build();
      }
      // Insert intended dimensions if they aren't already present.
      if (summary.getIntendedDimensions() == null) {
         DefaultCoords.Builder builder = new DefaultCoords.Builder();
         for (String axis : getAxes()) {
            builder.index(axis, getAxisLength(axis));
         }
         summary = summary.copy().intendedDimensions(builder.build()).build();
      }
      try {
         DefaultDatastore duplicate = new DefaultDatastore();

         Storage saver;
         if (mode == Datastore.SaveMode.MULTIPAGE_TIFF) {
            saver = new StorageMultipageTiff(duplicate,
               path, true, true,
               StorageMultipageTiff.getShouldSplitPositions());
         }
         else if (mode == Datastore.SaveMode.SINGLEPLANE_TIFF_SERIES) {
            saver = new StorageSinglePlaneTiffSeries(duplicate, path, true);
         }
         else {
            throw new IllegalArgumentException("Unrecognized mode parameter " + mode);
         }
         duplicate.setStorage(saver);
         duplicate.setSummaryMetadata(summary);
         // HACK HACK HACK HACK HACK
         // Copy images into the duplicate ordered by stage position index.
         // Doing otherwise causes errors when trying to write the OMEMetadata
         // (we get an ArrayIndexOutOfBoundsException when calling
         // MetadataTools.populateMetadata() in
         // org.micromanager.data.internal.multipagetiff.OMEMetadata).
         // Ideally we'd fix the OME metadata writer to be able to handle
         // images in arbitrary order, but that would require understanding
         // that code...
         ArrayList<Coords> tmp = new ArrayList<Coords>();
         for (Coords coords : getUnorderedImageCoords()) {
            tmp.add(coords);
         }
         java.util.Collections.sort(tmp, new java.util.Comparator<Coords>() {
            @Override
            public int compare(Coords a, Coords b) {
               int p1 = a.getIndex(Coords.STAGE_POSITION);
               int p2 = b.getIndex(Coords.STAGE_POSITION);
               return (p1 < p2) ? -1 : 1;
            }
         });
         for (Coords coords : tmp) {
            duplicate.putImage(getImage(coords));
         }
         setSavePath(path);
         freeze();
         return true;
      }
      catch (java.io.IOException e) {
         ReportingUtils.showError(e, "Failed to save image data");
      }
      catch (DatastoreFrozenException e) {
         ReportingUtils.logError("Couldn't modify newly-created datastore; this should never happen!");
      }
      return false;
   }

   @Override
   public int getNumImages() {
      if (storage_ != null) {
         return storage_.getNumImages();
      }
      return -1;
   }
}